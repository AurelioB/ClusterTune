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
    @Volatile private var configuredMethodId: String? = null

    val isAvailable: Boolean get() = selectedMethod() != null
    val selectedMethodId: String? get() = selectedMethod()?.id
    /** Persisted selection without probing or auto-detection. */
    val configuredMethodIdSnapshot: String? get() = configuredMethodId
    val availableMethodIds: List<String> get() = methods.map { it.id }

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
        for (method in autoDetectionOrder.mapNotNull(byId::get)) {
            if (method.probe().isAvailable) return method.also { cachedMethod = it }
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
        val DEFAULT_AUTO_DETECTION_ORDER = listOf("pserver-stdout", "root-shell")

        fun default(context: Context): PrivilegedExecutionResolver = PrivilegedExecutionResolver(
            listOf(PServerExecutionMethod(RootExec()), RootShellExecutionMethod()),
        )
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
        return if (rootExec.pServerAvailable) ExecutionProbeResult(true)
        else ExecutionProbeResult(false, "PServerBinder not available")
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
            if (output.trim() == PROBE_MARKER) ExecutionProbeResult(true)
            else ExecutionProbeResult(false, "su did not return expected probe output")
        },
        onFailure = { ExecutionProbeResult(false, it.message ?: "su probe failed") },
    )

    override fun launchHost(request: HostLaunchRequest): Result<Unit> =
        runner.launchHost(hostLauncher(request))
}

internal class RootHostLauncher {
    fun probe(): Result<String> = run("echo $PROBE_MARKER")
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
        if (process.exitValue() != 0 && stderr.contains("invalid uid/gid '-c'")) {
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

private const val PROBE_MARKER = "clustertune-exec-probe-ok"

private fun hostLauncher(request: HostLaunchRequest): String = buildString {
    append("cd ${shellQuote(request.workingDirectory)} && sh ${shellQuote(request.launcherScript)}")
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
