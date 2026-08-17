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
    /** Serialises command traffic over the shared adb shell. */
    private val shellUseLock: Any = Any(),
    private val persistentInjector: ((targetPackage: String, command: String, pid: Int, trigger: () -> Unit) -> Boolean)? = null,
    private val targetPackage: String = GAME_ASSISTANT_PKG,
    private val sharedDir: File = defaultSharedDir(),
) : PrivilegedExecutionMethod {

    override val id: String = "jdwp-inject"

    @Volatile
    private var cachedProbe: Pair<Long, ExecutionProbeResult>? = null

    /**
     * Paths whose last read came back empty, with the time it happened.
     *
     * Some sysfs nodes are simply not readable by us: on the Odin 2 Mini
     * scaling_min_freq (every policy) and scaling_max_freq (policy0) ship as
     * 0660 system:system, and neither the app's uid nor the adb shell user is
     * system. The live-state flow reads those nodes once a second, and each
     * failure fired a SECOND shell command to log the diagnostic — six adb
     * round-trips per second, indefinitely, for values that were never going
     * to arrive.
     *
     * Retrying is still worth doing occasionally, because an apply changes the
     * mode (the apply script now adds other-read), so entries expire after
     * [UNREADABLE_TTL_MS] and all are dropped after a successful injection.
     *
     * These live on the COMPANION, not the instance. AppContainer is built by
     * MainActivity, the overlay service and the boot receiver, and only
     * WirelessDebugConnectionManager is a true process-wide singleton — each
     * container builds its own resolver and therefore its own copy of this
     * class. Per-instance caches meant two containers each logged the same
     * diagnostic and each kept re-reading the same dead path, which is exactly
     * what the 17:50:14 and 17:50:15 duplicate diagnostics show.
     */
    private val unreadablePaths = Companion.unreadablePaths

    /** Paths whose `ls -lZ` diagnostic has already been logged in this process. */
    private val diagnosedPaths = Companion.diagnosedPaths

    private fun isKnownUnreadable(path: String): Boolean {
        val seenAt = unreadablePaths[path] ?: return false
        if (System.currentTimeMillis() - seenAt > UNREADABLE_TTL_MS) {
            unreadablePaths.remove(path)
            return false
        }
        return true
    }

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
        val status = prepareStatusFile()
        val result = synchronized(shellUseLock) {
        runCatching {
            val scriptPath = stageScript(
                scriptName,
                if (status != null) wrapForStatus(scriptContents, status.absolutePath) else scriptContents,
            )
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
                if (!ok) {
                    com.wuyr.jdwp_injector.debug.JdwpDebugLog.w("APPLY/jdwp: persistent injector returned FALSE (pid=$pid)")
                    throw IllegalStateException("Injection failed")
                }
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.d("APPLY/jdwp: persistent injection OK (pid=$pid)")
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
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.d("APPLY/jdwp: injection dispatched OK")
            // An apply script chmods the nodes it touches, so a path that was
            // unreadable a moment ago may be readable now. Forget every
            // suppression and let the next read find out.
            unreadablePaths.clear()
            null
        }.onFailure {
            Log.w(TAG, "executeScript: FAILED", it)
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.w("APPLY/jdwp: executeScript FAILED", it)
            shellInvalidator?.invoke()
        }
        }
        // Outside the lock on purpose — see readAndLogStatus.
        if (result.isSuccess) readAndLogStatus(status)
        return result
    }

    /**
     * Reads a (possibly privileged) file by having the system context copy it
     * into the shared dir, then reading it back as the app. Mirrors the
     * file-output pattern used by the PServer fallback method.
     */
    override fun readText(path: String): String? {
        connectionProvider() ?: return null
        // Do not spend an adb round-trip (plus a diagnostic round-trip) on a
        // node we already know we cannot read. Callers treat null exactly as
        // they treated the empty read, so behaviour is unchanged.
        if (isKnownUnreadable(path)) return null
        // Reads do NOT need system privileges — the adb shell user can read these
        // sysfs nodes directly. The previous implementation performed a FULL JDWP
        // injection per read (findTargetPid, connect2jdwp, attach, Runtime.exec,
        // resumeVM, dispose, then poll a file for up to a second).
        //
        // That was survivable until 1.0.2 added minimum-frequency reads
        // (readCurrentMinValues + cpuinfo_min_freq). Those nodes aren't
        // world-readable here, so every state refresh triggered several
        // injections in a loop. Logs showed findTargetPid firing in pairs every
        // 2s with interleaved, corrupted shell output — concurrent traffic on one
        // adb socket — until a thread wedged holding the apply lock and button
        // presses stopped doing anything at all.
        // Safety net: if a caller reaches us on the main thread, do the socket
        // work on a worker instead of throwing NetworkOnMainThreadException
        // (which killed the shared shell and forced a reconnect on every read).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            var result: String? = null
            val worker = Thread { result = readTextBlocking(path) }
            worker.isDaemon = true
            worker.start()
            worker.join(5_000)
            return result
        }
        return readTextBlocking(path)
    }

    private fun readTextBlocking(path: String): String? {
        return synchronized(shellUseLock) {
        val shell = sharedShellProvider?.invoke() ?: return@synchronized null
        runCatching {
            // Markers make parsing robust against the shell echoing the command
            // back (which is what corrupted the old line-index parsing).
            // The markers are SPLIT in the command text ("" is removed by the
            // shell). So the echoed command contains __CT_READ""_BEGIN__ while
            // only the real output contains __CT_READ_BEGIN__. Searching for the
            // joined form therefore can never match the echo — which matters
            // because the adb "shell:" service allocates a PTY that echoes input
            // and hard-wraps it with backspace control characters.
            val raw = shell.sendShellCommand(
                "echo __CT_READ\"\"_BEGIN__; cat '${path}' 2>/dev/null; " +
                    "echo __CT_READ\"\"_END__",
            )
            // Strip terminal control noise (backspaces / CR) before parsing.
            val clean = raw.replace("\b", "").replace("\r", "")
            val beginIdx = clean.lastIndexOf(READ_BEGIN)
            if (beginIdx < 0) return@runCatching null
            val afterBegin = beginIdx + READ_BEGIN.length
            val endIdx = clean.indexOf(READ_END, afterBegin)
            if (endIdx < 0) return@runCatching null
            val value = clean.substring(afterBegin, endIdx)
                .trim()
                .takeIf { it.isNotEmpty() }
            if (value == null) {
                // The read produced nothing. stderr was being discarded, so the
                // real reason (permission denied, I/O error, missing node) was
                // invisible. Re-run capturing stderr plus the file mode/owner so
                // the cause is in the log. This is what identified the cause:
                // `-rw-rw---- system system` plus `cat: Permission denied`, i.e.
                // a DAC problem, not a transport one.
                //
                // Log it ONCE per path per process. The mode does not change
                // between reads, so repeating the diagnostic at 1 Hz only
                // doubled the traffic and buried the rest of the log.
                unreadablePaths[path] = System.currentTimeMillis()
                if (diagnosedPaths.add(path)) {
                    runCatching {
                        val diag = shell.sendShellCommand(
                            "echo __CT_READ\"\"_BEGIN__; ls -lZ '${path}' 2>&1; " +
                                "cat '${path}' 2>&1; echo __CT_READ\"\"_END__",
                        ).replace("\b", "").replace("\r", "")
                        // Keep the tail, not the head: the command itself is
                        // echoed back first on some shells and would otherwise
                        // consume the whole budget before `ls -lZ` output.
                        val trimmed = diag.replace("\n", " | ").let { line ->
                            if (line.length > 400) line.takeLast(400) else line
                        }
                        com.wuyr.jdwp_injector.debug.JdwpDebugLog.w(
                            "readText('${path}') EMPTY — diag: $trimmed " +
                                "(further reads of this path are suppressed for " +
                                "${UNREADABLE_TTL_MS / 1000}s)",
                        )
                    }
                }
            } else {
                unreadablePaths.remove(path)
            }
            value
        }.getOrElse { error ->
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.w(
                "readText('${path}') failed: ${error.javaClass.simpleName}: ${error.message}",
            )
            shellInvalidator?.invoke()
            null
        }
        }
    }

    /** Not part of the 1.0.2 interface; kept as a plain helper. */
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
        // Marker-delimited so the PTY's echo of this very command can't be
        // mistaken for output (the shell echoes input and wraps it with
        // backspaces — that is what produced the garbled `raw=` in logs).
        val rawOut = adb.sendShellCommand(
            "echo __CT_READ\"\"_BEGIN__; pidof ${targetPackage}; echo __CT_READ\"\"_END__",
        ).replace("\b", "").replace("\r", "")
        val b = rawOut.lastIndexOf(READ_BEGIN)
        val e = if (b >= 0) rawOut.indexOf(READ_END, b + READ_BEGIN.length) else -1
        val raw = if (b >= 0 && e > b) {
            rawOut.substring(b + READ_BEGIN.length, e)
        } else {
            rawOut
        }
        // Do NOT assume the pid is on a fixed line. This used to read line index
        // 1 unconditionally, which only holds when the shell echoes something
        // first — on a freshly opened shell it doesn't, so the pid landed on
        // line 0 and we reported "GameAssistant is not running" while it was
        // demonstrably running. Scan every line for the first plausible pid.
        // pidof returns space-separated pids; take the first valid one.
        val pid = raw.split(Regex("\\s+"))
            .mapNotNull { it.trim().toIntOrNull() }
            .firstOrNull { it > 0 }
            ?: 0
        com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
            "findTargetPid: raw='${raw.replace("\n", "\\n").take(120)}' -> pid=$pid",
        )
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

    /**
     * Creates (or truncates) the status file the wrapped script writes into.
     *
     * The APP creates it, not the script, so the app is guaranteed to be able to
     * read it back: a file that GameAssistant creates in shared storage is not
     * reliably readable by us. We only need the system side to be able to write
     * into a file that already exists, which [setWritable] with `ownerOnly=false`
     * allows.
     */
    private fun prepareStatusFile(): File? = runCatching {
        val f = File(sharedDirFile(), STATUS_FILE_NAME)
        f.writeText("")
        f.setReadable(true, false)
        f.setWritable(true, false)
        f
    }.getOrNull()

    /**
     * Wraps an apply script so the fire-and-forget injection stops being blind.
     *
     * The JDWP path has no stdout — the injected `Runtime.exec` runs inside
     * GameAssistant and nothing comes back — so until now a script that failed
     * halfway looked identical to one that succeeded, and the only signal was
     * the read-back. That is precisely the ambiguity in the policy0 reports: we
     * could not tell a failed write from a successful write we could not read.
     *
     * The wrapper redirects everything into a file the app owns and adds:
     *  - `id`, proving the script really is running as uid=system;
     *  - `ls -lZ` of every ceiling/floor node BEFORE and AFTER the writes, which
     *    is the on-device equivalent of the adb command that identified the 0660
     *    stock mode;
     *  - `set -x`, so each command and each variable expansion is traced —
     *    including `mode=0660`, the value that decides everything;
     *  - the real exit code. The script body runs in a SUBSHELL so its `set -e`
     *    can abort the body without stopping the wrapper from reporting.
     */
    private fun wrapForStatus(contents: String, statusPath: String): String = buildString {
        append("exec > '").append(statusPath).append("' 2>&1\n")
        append("echo ").append(STATUS_BEGIN).append("\n")
        append("id\n")
        append("ls -lZ ").append(SYSFS_NODE_GLOB).append("\n")
        append("echo ").append(STATUS_BEFORE_END).append("\n")
        append("set -x\n")
        append("(\n")
        append(contents)
        append("\n)\n")
        append("ct_rc=\$?\n")
        append("set +x\n")
        append("echo ct-rc=\$ct_rc\n")
        append("ls -lZ ").append(SYSFS_NODE_GLOB).append("\n")
        append("echo ").append(STATUS_END).append("\n")
    }

    /**
     * Waits briefly for the injected script to finish, then logs what it did.
     *
     * Called OUTSIDE the shell lock: the apply path and every verification read
     * contend for it, and holding it while polling a file would stall them.
     */
    private fun readAndLogStatus(status: File?) {
        if (status == null) return
        var text = ""
        val deadline = System.currentTimeMillis() + STATUS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            text = runCatching { status.readText() }.getOrDefault("")
            if (text.contains(STATUS_END)) break
            runCatching { Thread.sleep(STATUS_POLL_MS) }
        }
        if (text.isBlank()) {
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.w(
                "APPLY/status: script produced no output within ${STATUS_WAIT_MS}ms " +
                    "(did the injected exec run at all?)",
            )
            return
        }
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val rc = lines.firstOrNull { it.startsWith("ct-rc=") }?.removePrefix("ct-rc=")
        val completed = text.contains(com.aure.clustertune.root.PerformanceCommandBuilder.COMPLETION_MARKER)
        val truncated = !text.contains(STATUS_END)
        com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
            "APPLY/status: rc=${rc ?: "?"} completionMarker=$completed" +
                if (truncated) " (script still running or died: no end marker)" else "",
        )
        // The node listings and anything that looks like a failure are always
        // logged; the full command trace only when something actually went
        // wrong, so a healthy apply costs a handful of lines instead of forty.
        val healthy = rc == "0" && completed && !truncated
        lines.forEach { line ->
            val isListing = line.contains("scaling_max_freq") || line.contains("scaling_min_freq")
            val isIdentity = line.startsWith("uid=")
            val isProblem = line.contains("Permission denied") || line.contains("ct-policy-failed") ||
                line.startsWith("Failed to write") || line.contains("Read-only") ||
                line.contains("No such file") || line.contains("Invalid argument") ||
                line.contains("Operation not permitted")
            if (isListing || isIdentity || isProblem || !healthy) {
                if (line != STATUS_BEGIN && line != STATUS_END && line != STATUS_BEFORE_END) {
                    com.wuyr.jdwp_injector.debug.JdwpDebugLog.d("APPLY/status| $line")
                }
            }
        }
    }

    private fun unavailable(reason: String) =
        ExecutionProbeResult(isAvailable = false, supportsStdout = false, failureReason = reason)

    companion object {
        const val TAG = "ClusterTuneJdwp"
        private const val PROBE_CACHE_MS = 5000L

        /** How long an empty read suppresses further reads of the same path. */
        private const val UNREADABLE_TTL_MS = 60_000L

        /** Process-wide; see the instance aliases for why these cannot be per-instance. */
        private val unreadablePaths = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val diagnosedPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        private const val READ_BEGIN = "__CT_READ_BEGIN__"
        private const val READ_END = "__CT_READ_END__"
        const val GAME_ASSISTANT_PKG = "com.odin2.gameassistant"

        private const val SHARED_DIR_NAME = "ClusterScripts"

        /** Where the injected script's own output is captured. */
        private const val STATUS_FILE_NAME = "ct-apply-status.txt"
        private const val STATUS_BEGIN = "ct-status-begin"
        private const val STATUS_BEFORE_END = "ct-status-before-end"
        private const val STATUS_END = "ct-status-end"
        private const val STATUS_WAIT_MS = 1200L
        private const val STATUS_POLL_MS = 50L
        private const val SYSFS_NODE_GLOB =
            "/sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq " +
                "/sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq"

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
