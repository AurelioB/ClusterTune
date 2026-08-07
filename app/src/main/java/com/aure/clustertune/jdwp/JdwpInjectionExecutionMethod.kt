package com.aure.clustertune.jdwp

import android.annotation.SuppressLint
import android.os.Environment
import android.util.Log
import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.wuyr.jdwp_injector.adb.AdbClient
import com.wuyr.jdwp_injector.debugger.Debugger
import java.io.File

/**
 * A [PrivilegedExecutionMethod] that runs profile scripts as uid=system on an
 * UNROOTED device, by injecting a Runtime.exec() call into GameAssistant
 * (com.odin2.gameassistant) over JDWP via on-device wireless debugging.
 *
 * Why this works: GameAssistant ships android:debuggable="true" while running
 * as sharedUserId=android.uid.system. Attaching a debugger to it lets us make
 * it execute code as system — enough to write scaling_max_freq. No root.
 *
 * The transport (adb-over-wireless-debugging + JDWP) is provided by the
 * vendored `jdwp-injector` module, adapted from
 * github.com/wuyr/jdwp-injector-for-android (Apache-2.0).
 *
 * Unlike wuyr's JdwpInjector.start(), we do NOT stage a dex via run-as (which
 * fails on a system app). Instead we directly invoke
 * Runtime.getRuntime().exec("sh <script>") — the script being one ClusterTune
 * already builds (chmod + echo into scaling_max_freq). The spawned process is
 * a child of GameAssistant and therefore also runs as system.
 *
 * Connection info (host/port of the local wireless-debugging adbd) must be
 * supplied by [connectionProvider]; the wireless pairing/port-resolution UI
 * populates it. If it returns null, this method reports unavailable.
 */
class JdwpInjectionExecutionMethod(
    private val connectionProvider: () -> AdbConnectionInfo?,
    private val sharedShellProvider: (() -> AdbClient?)? = null,
    private val shellInvalidator: (() -> Unit)? = null,
    private val persistentInjector: ((targetPackage: String, command: String, pid: Int, trigger: () -> Unit) -> Boolean)? = null,
    private val targetPackage: String = GAME_ASSISTANT_PKG,
    private val sharedDir: File = defaultSharedDir(),
) : PrivilegedExecutionMethod {

    override val id: String = "jdwp-inject"

    @Volatile
    private var cachedProbe: Pair<Long, ExecutionProbeResult>? = null

    override fun probe(): ExecutionProbeResult {
        // Cheap probe: this is called frequently (state refreshes, app-monitor),
        // so it must NOT open a wireless adb connection every time — doing so
        // hammers adbd and spikes CPU. Availability = "we have a wireless-debug
        // connection". The actual GameAssistant/injection check happens lazily
        // at executeScript time. Result is cached briefly to avoid churn.
        val now = System.currentTimeMillis()
        cachedProbe?.let { (ts, result) ->
            if (now - ts < PROBE_CACHE_MS) return result
        }
        val conn = connectionProvider()
        val result = if (conn == null) {
            unavailable("Wireless debugging not connected")
        } else {
            ExecutionProbeResult(isAvailable = true, supportsStdout = false)
        }
        com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
            "probe(jdwp-inject): conn=${conn?.let { "${it.host}:${it.port}" } ?: "null"} -> available=${result.isAvailable}"
        )
        cachedProbe = now to result
        return result
    }

    /**
     * Writes [scriptContents] to the shared dir, then injects a Runtime.exec
     * that runs it as system inside GameAssistant. Fire-and-forget: the
     * injected exec's stdout is not captured (supportsStdout=false).
     */
    /**
     * [captureResult] is accepted for interface compatibility but ignored: the
     * injected `Runtime.exec` runs inside GameAssistant and its stdout is not
     * routed back to us (probe reports supportsStdout=false). Callers that need
     * output must use [readText] against a file the script writes.
     */
    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        val conn = connectionProvider()
        if (conn == null) {
            Log.w(TAG, "executeScript: no wireless connection")
            return Result.failure(IllegalStateException("Wireless debugging not connected"))
        }
        return runCatching {
            val scriptPath = stageScript(scriptName, scriptContents)
            Log.d(TAG, "executeScript: staged '$scriptName' -> $scriptPath (${scriptContents.length} bytes)")
            val shell = sharedShellProvider?.invoke()
            val injector = persistentInjector
            if (shell != null && injector != null) {
                // Preferred path: reuse the persistent JDWP session + shared
                // shell (keeps the adb transport open -> no repeated heads-up).
                val pid = findTargetPid(shell)
                if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
                Log.d(TAG, "executeScript: injecting into GameAssistant pid=$pid (persistent)")
                val ok = injector(targetPackage, "sh ${scriptPath}", pid) {
                    shell.sendShellCommand("am attach-agent ${targetPackage} /")
                }
                if (!ok) throw IllegalStateException("Injection failed")
            } else if (shell != null) {
                val pid = findTargetPid(shell)
                if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
                Log.d(TAG, "executeScript: injecting into GameAssistant pid=$pid (shared shell)")
                injectExec(conn, shell, "sh ${scriptPath}")
            } else {
                AdbClient.openShell(conn.host, conn.port).use { adb ->
                    val pid = findTargetPid(adb)
                    if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
                    Log.d(TAG, "executeScript: injecting into GameAssistant pid=$pid (temp shell)")
                    injectExec(conn, adb, "sh ${scriptPath}")
                }
            }
            Log.d(TAG, "executeScript: injection dispatched OK")
            null
        }.onFailure {
            Log.w(TAG, "executeScript: FAILED", it)
            shellInvalidator?.invoke()
        }
    }

    /**
     * Reads a (possibly privileged) file by having the system context copy it
     * into the shared dir, then reading it back as the app. Mirrors the
     * file-output pattern used by the PServer fallback method.
     */
    override fun readText(path: String): String? {
        val conn = connectionProvider() ?: return null
        return runCatching {
            val outFile = File(sharedDirFile(), "ct_read.out")
            outFile.delete()
            // Build a tiny copy script and run it as system.
            val copyScript = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("cat '${path}' > '${outFile.absolutePath}' 2>/dev/null")
                appendLine("chmod 666 '${outFile.absolutePath}' 2>/dev/null")
            }
            val scriptPath = stageScript("ct_read.sh", copyScript)
            AdbClient.openShell(conn.host, conn.port).use { adb ->
                val pid = findTargetPid(adb)
                if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
                injectExec(conn, adb, "sh ${scriptPath}")
            }
            // Give the injected process a moment to write the file.
            var text: String? = null
            repeat(10) {
                Thread.sleep(100)
                if (outFile.isFile) {
                    val t = outFile.readText().trim()
                    if (t.isNotEmpty()) { text = t; return@repeat }
                }
            }
            text
        }.getOrNull()
    }

    /**
     * Not part of the PrivilegedExecutionMethod interface as of ClusterTune
     * 1.0.2 (upstream removed it), so this is a plain helper rather than an
     * override. Kept because the JDWP path may still need to relax permissions
     * on a staged file it wrote as system.
     */
    fun makeReadable(path: String): Boolean {
        val conn = connectionProvider() ?: return false
        return runCatching {
            val script = buildString {
                appendLine("#!/system/bin/sh")
                appendLine("chmod 444 '${path}' 2>/dev/null")
            }
            val scriptPath = stageScript("ct_chmod.sh", script)
            AdbClient.openShell(conn.host, conn.port).use { adb ->
                val pid = findTargetPid(adb)
                if (pid <= 0) return@runCatching false
                injectExec(conn, adb, "sh ${scriptPath}")
                true
            }
        }.getOrDefault(false)
    }

    // ---- internals ----

    private fun findTargetPid(adb: AdbClient): Int = runCatching {
        val raw = adb.sendShellCommand(
            "ps -A -o PID,NAME | grep -w ${targetPackage} | awk 'NR==1{print \$1}'"
        )
        val pid = raw.split("\n").getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        Log.d(TAG, "findTargetPid: raw=${raw.replace("\n", "\\n")} -> pid=$pid")
        pid
    }.getOrElse {
        Log.w(TAG, "findTargetPid: error", it)
        0
    }

    /**
     * The core: attach to GameAssistant, get a running thread, invoke
     * Runtime.getRuntime().exec(command) as system, then detach.
     *
     * The trigger reuses wuyr's trick: watch a modification of
     * MessageQueue.mMessages, then `am attach-agent <proc> /` to make the
     * target's main looper run (no visible input, fires reliably).
     */
    private fun injectExec(conn: AdbConnectionInfo, shellAdb: AdbClient, command: String) {
        val pid = findTargetPid(shellAdb)
        if (pid <= 0) throw IllegalStateException("GameAssistant is not running")
        Log.d(TAG, "injectExec: attaching JDWP to pid=$pid, command='$command'")
        Debugger(AdbClient.connect2jdwp(conn.host, conn.port, pid)).use { debugger ->
            Log.d(TAG, "injectExec: waiting for thread via MessageQueue watch + attach-agent trigger")
            val threadId = debugger.setAndWaitForModificationEventArrive(
                "android.os.MessageQueue", "mMessages", "android.os.Message"
            ) {
                shellAdb.sendShellCommand("am attach-agent ${targetPackage} /")
            }
            Log.d(TAG, "injectExec: got threadId=$threadId; invoking Runtime.getRuntime()")
            try {
                val runtimeObjectId = debugger.invokeStaticMethod(
                    "java.lang.Runtime", "getRuntime",
                    returnTypeName = "java.lang.Runtime", threadId = threadId
                ).second as Long
                Log.d(TAG, "injectExec: Runtime obj=$runtimeObjectId; invoking exec()")
                debugger.invokeInstanceMethod(
                    runtimeObjectId, "java.lang.Runtime", "exec",
                    returnTypeName = "java.lang.Process", threadId = threadId,
                    "java.lang.String" to command
                )
                Log.d(TAG, "injectExec: exec() invoked")
            } finally {
                debugger.resumeVM()
                debugger.dispose()
                Log.d(TAG, "injectExec: resumed + disposed")
            }
        }
    }

    @SuppressLint("SdCardPath")
    private fun sharedDirFile(): File = sharedDir.apply { if (!exists()) mkdirs() }

    private fun stageScript(name: String, contents: String): String {
        val dir = sharedDirFile()
        val safeName = name.substringAfterLast('/').ifEmpty { "ct_script.sh" }
        val f = File(dir, safeName)
        f.writeText(contents)
        // world-readable so GameAssistant (in sdcard_rw/media_rw groups) can read it
        f.setReadable(true, false)
        return f.absolutePath
    }

    private fun unavailable(reason: String) =
        ExecutionProbeResult(isAvailable = false, supportsStdout = false, failureReason = reason)

    companion object {
        const val TAG = "ClusterTuneJdwp"
        private const val PROBE_CACHE_MS = 5000L
        const val GAME_ASSISTANT_PKG = "com.odin2.gameassistant"

        private const val SHARED_DIR_NAME = "ClusterScripts"

        /**
         * Reuses the same public-storage handoff location as OdinScriptHandoff:
         * Documents/ClusterScripts. This is writable by the app without runtime
         * permissions (scoped-storage carve-out for Documents on Android 10+),
         * and readable by GameAssistant (uid=system, in the external-storage
         * groups). Verified in ClusterTune's existing Odin script handoff.
         */
        @Suppress("DEPRECATION")
        fun defaultSharedDir(): File = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            SHARED_DIR_NAME,
        )
    }
}

/** Host/port of the on-device wireless-debugging adbd, once paired+connected. */
data class AdbConnectionInfo(
    val host: String,
    val port: Int,
)
