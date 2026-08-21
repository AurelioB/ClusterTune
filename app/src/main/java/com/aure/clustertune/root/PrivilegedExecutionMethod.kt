package com.aure.clustertune.root

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ExecutionProbeResult(
    val isAvailable: Boolean,
    val failureReason: String? = null,
)

/** The only operation an execution method may perform after probing: start the host. */
data class HostLaunchRequest(
    val workingDirectory: String,
    val launcherScript: String,
)

interface PrivilegedExecutionMethod {
    val id: String
    fun probe(): ExecutionProbeResult
    fun launchHost(request: HostLaunchRequest): Result<Unit>
}

class PrivilegedExecutionResolver(
    private val methods: List<PrivilegedExecutionMethod>,
    private val autoDetectionOrder: List<String> = DEFAULT_AUTO_DETECTION_ORDER,
) {
    private val lock = Any()
    @Volatile private var generation = 0L
    private var cachedMethod: PrivilegedExecutionMethod? = null

    /**
     * Reports the method a live privileged host is attached through, if any.
     *
     * Set by AppContainer once the host client exists. Consulted before probing
     * so an already-working host always wins; it never calls back into the
     * resolver, so there is no cycle.
     */
    @Volatile
    var runningHostMethodProvider: (() -> String?)? = null
    @Volatile private var configuredMethodId: String? = null

    val isAvailable: Boolean get() = selectedMethod() != null
    val selectedMethodId: String? get() = selectedMethod()?.id
    /** Persisted selection without probing or auto-detection. */
    val configuredMethodIdSnapshot: String? get() = configuredMethodId
    val availableMethodIds: List<String> get() = methods.map { it.id }

    /**
     * Direct lookup by id, bypassing probing. Used to offer a method that cannot
     * report itself available yet — jdwp-inject before a connection exists.
     */
    fun methodById(methodId: String): PrivilegedExecutionMethod? = synchronized(lock) {
        methods.firstOrNull { it.id == methodId }
    }

    fun setConfiguredMethodId(methodId: String?) = synchronized(lock) {
        if (configuredMethodId != methodId) {
            configuredMethodId = methodId
            cachedMethod = null
            generation++
        }
    }

    fun autoDetectBestMethod(forceReprobe: Boolean = true): String? = synchronized(lock) {
        val method = selectBestMethod(forceReprobe)
        configuredMethodId = method?.id
        generation++
        method?.id
    }

    data class SelectionSnapshot(val methodId: String?, val generation: Long)
    fun selectionSnapshot(): SelectionSnapshot = synchronized(lock) {
        SelectionSnapshot(selectedMethodLocked(false)?.id, generation)
    }

    fun selectedMethod(forceReprobe: Boolean = false): PrivilegedExecutionMethod? = synchronized(lock) {
        selectedMethodLocked(forceReprobe)
    }

    private fun selectedMethodLocked(forceReprobe: Boolean): PrivilegedExecutionMethod? {
        if (!forceReprobe) cachedMethod?.let { return it }
        cachedMethod = null
        configuredMethodId?.let { id ->
            methods.firstOrNull { it.id == id }?.let { method ->
                if (method.probe().isAvailable) return method.also { cachedMethod = it }
            }
        }
        return selectBestMethod(true)
    }

    private fun selectBestMethod(forceReprobe: Boolean): PrivilegedExecutionMethod? {
        if (!forceReprobe) cachedMethod?.let { return it }
        cachedMethod = null
        val byId = methods.associateBy { it.id }
        // A host that is already running is proof, not a prediction.
        //
        // Probing asks "could this method start a host now", which for
        // jdwp-inject means "is there a live wireless-debugging connection". With
        // Wi-Fi off that is false even while the host it started is up and
        // serving — so Auto detect reported nothing available and the app claimed
        // no privileged execution method, on a device that was applying profiles
        // perfectly. Trusting the running host also stops detection from tearing
        // down something that works in favour of something that merely probes.
        runningHostMethodProvider?.invoke()?.let { runningId ->
            byId[runningId]?.let { method ->
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
                    "selectBestMethod: host already running via $runningId; keeping it",
                )
                return method.also { cachedMethod = it }
            }
        }
        for (method in autoDetectionOrder.mapNotNull(byId::get)) {
            // Timed because detection sits on the cold path for both app start
            // and the quick tile: nothing can be applied until it returns. Which
            // probe is slow is not obvious - `su` and the PServer binder call are
            // both plausible - so measure rather than guess.
            val startedAt = System.currentTimeMillis()
            val probe = method.probe()
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= SLOW_PROBE_LOG_THRESHOLD_MS) {
                com.wuyr.jdwp_injector.debug.JdwpDebugLog.w(
                    "probe(${method.id}): ${elapsed}ms available=${probe.isAvailable}" +
                        (probe.failureReason?.let { " reason=$it" } ?: ""),
                )
            }
            if (probe.isAvailable) return method.also { cachedMethod = it }
        }
        return null
    }

    fun launchHost(snapshot: SelectionSnapshot, request: HostLaunchRequest): Result<Unit> = synchronized(lock) {
        val current = SelectionSnapshot(selectedMethodLocked(false)?.id, generation)
        if (snapshot != current || snapshot.methodId == null) {
            return Result.failure(IllegalStateException("privileged execution method changed"))
        }
        methods.firstOrNull { it.id == snapshot.methodId }
            ?.launchHost(request)
            ?: Result.failure(IllegalStateException("selected privileged method disappeared"))
    }

    companion object {
        /** Probes slower than this are logged; detection blocks on them. */
        private const val SLOW_PROBE_LOG_THRESHOLD_MS = 250L

        // jdwp-inject is tried last: it is the no-root fallback, and unlike the
        // other two it needs a wireless-debugging connection to start the host.
        val DEFAULT_AUTO_DETECTION_ORDER = listOf("pserver-stdout", "root-shell", "jdwp-inject")

        fun default(
            context: Context,
            jdwpConnectionProvider: (() -> com.aure.clustertune.jdwp.AdbConnectionInfo?)? = null,
            jdwpSharedShellProvider: (() -> com.wuyr.jdwp_injector.adb.AdbClient?)? = null,
            jdwpShellInvalidator: (() -> Unit)? = null,
            jdwpPersistentInjector: ((String, String, Int, () -> Unit) -> Boolean)? = null,
            jdwpShellUseLock: Any = Any(),
        ): PrivilegedExecutionResolver {
            val methods = mutableListOf<PrivilegedExecutionMethod>(
                PServerExecutionMethod(RootExec()),
                RootShellExecutionMethod(),
            )
            if (jdwpConnectionProvider != null) {
                methods += com.aure.clustertune.jdwp.JdwpHostExecutionMethod(
                    context = context,
                    connectionProvider = jdwpConnectionProvider,
                    sharedShellProvider = jdwpSharedShellProvider,
                    shellInvalidator = jdwpShellInvalidator,
                    persistentInjector = jdwpPersistentInjector,
                    shellUseLock = jdwpShellUseLock,
                )
            }
            return PrivilegedExecutionResolver(methods)
        }
    }
}

/**
 * The persisted id retains its historical name for settings compatibility. PServer is now used
 * only to launch the long-lived Binder host; probing checks binder liveness and never runs a
 * command or captures stdout.
 */
internal class PServerExecutionMethod(
    private val rootExec: PServerHostExecutor = RootExec(),
) : PrivilegedExecutionMethod {
    override val id = "pserver-stdout"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(false, "PServerBinder not available")
        }
        // Presence is not permission — see PServerHostExecutor.verify. Reporting
        // available here without a real transaction makes auto-detection pick
        // PServer on devices whose SELinux policy refuses the call, which then
        // shadows a method that does work.
        return rootExec.verify().fold(
            onSuccess = { ExecutionProbeResult(true) },
            onFailure = {
                ExecutionProbeResult(
                    false,
                    "PServer rejected the call (${it.message ?: "transaction failed"})",
                )
            },
        )
    }

    override fun launchHost(request: HostLaunchRequest): Result<Unit> =
        rootExec.launchHost(hostLauncher(request))
}

internal class RootShellExecutionMethod(
    private val runner: RootHostLauncher = RootHostLauncher(),
) : PrivilegedExecutionMethod {
    override val id = "root-shell"

    override fun probe(): ExecutionProbeResult = runner.probe().fold(
        onSuccess = { output ->
            if (output.trim() == "0") ExecutionProbeResult(true)
            else ExecutionProbeResult(false, "su did not run as root")
        },
        onFailure = { ExecutionProbeResult(false, it.message ?: "su probe failed") },
    )

    override fun launchHost(request: HostLaunchRequest): Result<Unit> =
        runner.launchHost(hostLauncher(request))
}

internal class RootHostLauncher {
    fun probe(): Result<String> = run("id -u")
        .map { it.stdout }

    fun launchHost(command: String): Result<Unit> = run(command).map { Unit }

    private fun run(command: String): Result<ShellCommandResult> = runCatching {
        var process = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (_: IOException) {
            ProcessBuilder("su", "0", "sh", "-c", command).start()
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("root host launch timed out")
        }
        var stdout = process.inputStream.bufferedReader().use { it.readText() }
        var stderr = process.errorStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) {
            process = ProcessBuilder("su", "0", "sh", "-c", command).start()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("root host launch timed out")
            }
            stdout = process.inputStream.bufferedReader().use { it.readText() }
            stderr = process.errorStream.bufferedReader().use { it.readText() }
        }
        check(process.exitValue() == 0) { stderr.ifBlank { "root host launch failed" } }
        ShellCommandResult(stdout)
    }
}

private data class ShellCommandResult(val stdout: String)

private fun hostLauncher(request: HostLaunchRequest): String = buildString {
    append("cd ${shellQuote(request.workingDirectory)} && sh ${shellQuote(request.launcherScript)}")
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
