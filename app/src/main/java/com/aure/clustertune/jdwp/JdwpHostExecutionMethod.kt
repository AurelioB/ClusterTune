package com.aure.clustertune.jdwp

import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import com.wuyr.jdwp_injector.debugger.Debugger
import java.io.File

/**
 * Starts the privileged host as uid=system on an UNROOTED device, by injecting
 * a `Runtime.exec()` call into GameAssistant (com.odin2.gameassistant) over
 * JDWP via on-device wireless debugging.
 *
 * Why this works: GameAssistant ships `android:debuggable="true"` while running
 * as `sharedUserId=android.uid.system`. Attaching a debugger lets us make it
 * execute a command as system. `ClusterTuneHostEntry.isPrivilegedHostUid`
 * accepts uid 0 **or** 1000, so the host is happy to run at system rather than
 * root — no capability beyond what GameAssistant already has is required.
 *
 * The transport is the vendored `jdwp-injector` module, adapted from
 * github.com/wuyr/jdwp-injector-for-android (Apache-2.0).
 *
 * Scope note: since 1.2.x an execution method's ONLY job is to start the host
 * once. Reads, writes and applies then travel over Binder to the host process,
 * so all the shell/stdout/marker machinery this class used to carry is gone.
 * Wireless debugging is needed only to start the host; once it is up, applies
 * keep working with Wi-Fi off until the next reboot.
 *
 * ## Why the launcher is rewritten rather than run as handed to us
 *
 * [ClusterTuneHostClient] builds a launcher that points `CLASSPATH` at dex
 * files under `context.codeCacheDir` and `cd`s into that directory. That is
 * fine for root, which bypasses everything. It does NOT work at uid=system on
 * this device, for two independent reasons:
 *
 *  - **DAC**: `/data/user/0/<pkg>` is 0700 owned by the app's own uid. uid 1000
 *    is not the owner and has no CAP_DAC_OVERRIDE, so it cannot even traverse
 *    into `code_cache`, however world-readable the dex files themselves are.
 *  - **SELinux**: the injected process inherits GameAssistant's context,
 *    `u:r:system_app:s0` (confirmed on-device: `uid=1000(system) …
 *    context=u:r:system_app:s0`). `system_app` has no read on `app_data_file`.
 *
 * Both are avoided by pointing `CLASSPATH` at the APK instead. The APK is
 * labelled `apk_data_file`, is world-readable, and lives on a traversable path
 * — this is the same route Shizuku uses to start its own privileged process,
 * and it needs no permission changes on app-private storage. The APK contains
 * `classes*.dex`, so it carries `ClusterTuneHostEntry` already; the extra dex
 * extraction upstream performs is simply not needed on this path.
 *
 * The working directory moves to public storage for the same reason: the
 * launcher redirects its output to `./host-startup.log`, and uid=system must be
 * able to create that file. Documents/ClusterScripts is writable by the app
 * without a runtime permission and readable/writable by uid=system, which this
 * fork has relied on for a long time.
 */
class JdwpHostExecutionMethod(
    private val context: Context,
    private val connectionProvider: () -> AdbConnectionInfo?,
    private val sharedShellProvider: (() -> AdbClient?)? = null,
    private val shellInvalidator: (() -> Unit)? = null,
    /** Serialises command traffic over the shared adb shell. */
    private val shellUseLock: Any = Any(),
    private val persistentInjector: ((targetPackage: String, command: String, pid: Int, trigger: () -> Unit) -> Boolean)? = null,
    private val targetPackage: String = GAME_ASSISTANT_PKG,
    private val hostDir: File = defaultHostDir(),
) : PrivilegedExecutionMethod {

    override val id: String = METHOD_ID

    // Process-wide. Every AppContainer used to build its own method instance
    // with its own cache, so one instance could hold a stale "unavailable" from
    // seconds earlier while another had already connected — which is how a
    // freshly established connection still reported detected=null.
    private var cachedProbe: Pair<Long, ExecutionProbeResult>?
        get() = sharedProbeCache
        set(value) { sharedProbeCache = value }

    /**
     * Availability means "we have a wireless-debugging connection", nothing
     * more. This is polled often, so it must never open an adb connection of
     * its own — doing that used to hammer adbd and spike CPU. Whether
     * GameAssistant is actually injectable is discovered lazily in
     * [launchHost].
     */
    override fun probe(): ExecutionProbeResult {
        val now = System.currentTimeMillis()
        cachedProbe?.let { (ts, result) -> if (now - ts < PROBE_CACHE_MS) return result }
        val conn = connectionProvider()
        val result = if (conn == null) {
            ExecutionProbeResult(false, "Wireless debugging not connected")
        } else {
            ExecutionProbeResult(true)
        }
        JdwpDebugLog.d(
            "probe($METHOD_ID): conn=${conn?.let { "${it.host}:${it.port}" } ?: "null"} " +
                "-> available=${result.isAvailable}",
        )
        cachedProbe = now to result
        return result
    }

    override fun launchHost(request: HostLaunchRequest): Result<Unit> = runCatching {
        val conn = connectionProvider()
            ?: throw IllegalStateException("Wireless debugging not connected")

        val staged = stageLauncher(request)
        JdwpDebugLog.d("launchHost: staged launcher at ${staged.absolutePath}")

        // Two bare tokens only. Runtime.exec(String) splits on whitespace with
        // StringTokenizer and does NOT honour quoting, so anything needing
        // quotes (the cd, the CLASSPATH assignment, the trailing &) has to live
        // inside the staged file rather than in this command.
        val command = "sh ${staged.absolutePath}"

        val shell = sharedShellProvider?.invoke()
        if (shell != null) {
            val pid = synchronized(shellUseLock) { findTargetPid(shell) }
            if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
            val injected = persistentInjector?.invoke(targetPackage, command, pid) {
                synchronized(shellUseLock) {
                    runCatching { shell.sendShellCommand("am attach-agent $targetPackage /") }
                }
            } ?: false
            if (injected) {
                JdwpDebugLog.d("launchHost: persistent injection OK (pid=$pid)")
                reportStartupLog()
                return@runCatching
            }
            JdwpDebugLog.d("launchHost: persistent injection unavailable; falling back")
        }

        runCatching { injectExec(conn, requireNotNull(shell) { "no adb shell" }, command) }
            .onFailure { shellInvalidator?.invoke(); throw it }
        reportStartupLog()
    }.onFailure { JdwpDebugLog.w("launchHost FAILED: ${it.message}") }

    // ---- launcher staging ---------------------------------------------------

    /**
     * Copies the host launcher into a directory uid=system can use, with
     * `CLASSPATH` repointed at the APK.
     *
     * Only the classpath assignment is rewritten; every argument the client
     * computed (service name, owner uid, generation, method id, package,
     * handoff nonce) is preserved byte-for-byte, so this stays correct if
     * upstream changes those. If the expected `CLASSPATH='…'` shape is ever not
     * found, the original text is staged unchanged and a warning is logged
     * rather than silently launching something malformed.
     */
    private fun stageLauncher(request: HostLaunchRequest): File {
        val source = File(request.workingDirectory, request.launcherScript)
        val original = source.readText()

        val apkPath = context.applicationInfo.sourceDir
        val rewritten = CLASSPATH_PATTERN.find(original)?.let { match ->
            original.replaceRange(match.range, "CLASSPATH='${shellEscape(apkPath)}'")
        } ?: original.also {
            JdwpDebugLog.w(
                "launchHost: CLASSPATH not found in launcher; using it unchanged. " +
                    "The host will probably fail to start as system.",
            )
        }

        val dir = hostDir.apply { if (!exists()) mkdirs() }
        // Recreated by the app every launch so it is app-owned and therefore
        // guaranteed readable back; a file uid=system creates here is not.
        File(dir, STARTUP_LOG).apply {
            writeText("")
            setReadable(true, false)
            setWritable(true, false)
        }
        return File(dir, LAUNCHER_NAME).apply {
            writeText("#!/system/bin/sh\ncd '${shellEscape(dir.absolutePath)}' || exit 1\n$rewritten")
            setReadable(true, false)
        }
    }

    /**
     * Surfaces the host's own startup output, which is otherwise invisible.
     *
     * Waits for the log to stop growing rather than dumping the first thing that
     * appears. The earlier version returned as soon as any content existed, so
     * it reliably captured only "entered args" and "looper prepared" and cut off
     * before the lines that actually matter — capability decisions such as
     * "omitting GPU domain", and the binder handoff.
     */
    private fun reportStartupLog() {
        val log = File(hostDir, STARTUP_LOG)
        val deadline = System.currentTimeMillis() + STARTUP_LOG_WAIT_MS
        var text = ""
        var stableFor = 0L
        while (System.currentTimeMillis() < deadline) {
            val current = runCatching { log.readText() }.getOrNull().orEmpty()
            if (current == text && text.isNotBlank()) {
                stableFor += STARTUP_LOG_POLL_MS
                if (stableFor >= STARTUP_LOG_SETTLE_MS) break
            } else {
                stableFor = 0
                text = current
            }
            Thread.sleep(STARTUP_LOG_POLL_MS)
        }
        if (text.isBlank()) {
            JdwpDebugLog.d("host| (no startup output within ${STARTUP_LOG_WAIT_MS}ms)")
            return
        }
        text.lineSequence().filter { it.isNotBlank() }.take(STARTUP_LOG_LINES).forEach {
            JdwpDebugLog.d("host| ${it.take(220)}")
        }
    }

    // ---- injection ----------------------------------------------------------

    private fun findTargetPid(shellAdb: AdbClient): Int = runCatching {
        val raw = shellAdb.sendShellCommand("pidof $targetPackage")
        // Scan every line for the first plausible pid. An older version took
        // line 1 unconditionally, which only holds when the shell echoes first;
        // on a freshly opened shell it does not, and the pid landed on line 0.
        val pid = raw.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .firstOrNull { it > 0 }
            ?: 0
        JdwpDebugLog.d("findTargetPid: raw='${raw.replace("\n", "\\n").take(120)}' -> pid=$pid")
        pid
    }.getOrElse {
        JdwpDebugLog.w("findTargetPid: error", it)
        0
    }

    /**
     * Attach to GameAssistant, get a running thread, invoke
     * `Runtime.getRuntime().exec(command)` as system, then detach.
     *
     * The trigger reuses wuyr's approach: watch a modification of
     * `MessageQueue.mMessages`, then `am attach-agent <proc> /` to make the
     * target's main looper run — no visible input, and it fires reliably.
     */
    private fun injectExec(conn: AdbConnectionInfo, shellAdb: AdbClient, command: String) {
        val pid = findTargetPid(shellAdb)
        if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
        JdwpDebugLog.d("injectExec: attaching JDWP to pid=$pid, command='$command'")
        Debugger(AdbClient.connect2jdwp(conn.host, conn.port, pid)).use { debugger ->
            val threadId = debugger.setAndWaitForModificationEventArrive(
                "android.os.MessageQueue", "mMessages", "android.os.Message",
            ) {
                shellAdb.sendShellCommand("am attach-agent $targetPackage /")
            }
            try {
                val runtimeObjectId = debugger.invokeStaticMethod(
                    "java.lang.Runtime", "getRuntime",
                    returnTypeName = "java.lang.Runtime", threadId = threadId,
                ).second as Long
                debugger.invokeInstanceMethod(
                    runtimeObjectId, "java.lang.Runtime", "exec",
                    returnTypeName = "java.lang.Process", threadId = threadId,
                    "java.lang.String" to command,
                )
                JdwpDebugLog.d("injectExec: exec() invoked")
            } finally {
                debugger.resumeVM()
                debugger.dispose()
            }
        }
    }

    private fun shellEscape(value: String): String = value.replace("'", "'\\''")

    companion object {
        const val TAG = "ClusterTuneJdwp"
        const val METHOD_ID = "jdwp-inject"
        const val GAME_ASSISTANT_PKG = "com.odin2.gameassistant"

        @Volatile
        private var sharedProbeCache: Pair<Long, ExecutionProbeResult>? = null

        private const val PROBE_CACHE_MS = 5000L
        private const val SHARED_DIR_NAME = "ClusterScripts"
        private const val HOST_DIR_NAME = "host"
        private const val LAUNCHER_NAME = "ct-launch-host.sh"
        private const val STARTUP_LOG = "host-startup.log"
        private const val STARTUP_LOG_WAIT_MS = 5000L
        private const val STARTUP_LOG_POLL_MS = 100L
        /** How long the log must stop growing before it is considered complete. */
        private const val STARTUP_LOG_SETTLE_MS = 600L
        private const val STARTUP_LOG_LINES = 40

        private val CLASSPATH_PATTERN = Regex("""CLASSPATH='(?:[^']|'\\'')*'""")

        /**
         * Documents/ClusterScripts/host — writable by the app with no runtime
         * permission (the scoped-storage carve-out for Documents) and
         * readable/writable by uid=system, which this fork has depended on
         * since the original script handoff.
         */
        /**
         * Ask a host left running by a previous app process to re-announce
         * itself, so this process can adopt it.
         *
         * The host stats this file only while it is unattached, and deletes it
         * when it acts on it. Creating it is the whole handshake — the app
         * cannot call an orphaned host directly, because the binder handle died
         * with the process that owned it.
         */
        @Volatile
        private var lastAdoptionRequestAt = 0L

        /**
         * Rate limit. MainActivity, the overlay service, the tile service and the
         * boot receiver each build their own AppContainer, and availability is
         * re-checked often, so an unguarded request wrote this file — and a log
         * line — dozens of times a minute for no benefit. The host only needs to
         * see it once to re-announce.
         */
        private const val ADOPTION_REQUEST_INTERVAL_MS = 15_000L

        fun requestAdoption(dir: File = defaultHostDir()): Boolean = runCatching {
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (now - lastAdoptionRequestAt < ADOPTION_REQUEST_INTERVAL_MS) return false
                lastAdoptionRequestAt = now
            }
            if (!dir.exists() && !dir.mkdirs()) return false
            File(dir, ADOPT_REQUEST_FILE).apply {
                writeText(now.toString())
                setReadable(true, false)
                setWritable(true, false)
            }
            JdwpDebugLog.d("adoption requested via ${File(dir, ADOPT_REQUEST_FILE).absolutePath}")
            true
        }.getOrElse {
            JdwpDebugLog.w("adoption request failed: ${it.message}")
            false
        }

        /** Must match ClusterTuneHostEntry.ADOPT_REQUEST_FILE. */
        private const val ADOPT_REQUEST_FILE = "adopt-request"

        @SuppressLint("SdCardPath")
        @Suppress("DEPRECATION")
        fun defaultHostDir(): File = File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                SHARED_DIR_NAME,
            ),
            HOST_DIR_NAME,
        )
    }
}
