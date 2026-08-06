package com.aure.clustertune.root

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

private const val PROBE_MARKER = "clustertune-exec-probe-ok"

data class ExecutionProbeResult(
    val isAvailable: Boolean,
    val supportsStdout: Boolean,
    val failureReason: String? = null,
)

interface PrivilegedExecutionMethod {
    val id: String

    fun probe(): ExecutionProbeResult

    fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?>

    fun readText(path: String): String?

    fun makeReadable(path: String): Boolean = false
}

class PrivilegedExecutionResolver(
    private val methods: List<PrivilegedExecutionMethod>,
    private val autoDetectionOrder: List<String> = DEFAULT_AUTO_DETECTION_ORDER,
) {
    private var cachedMethod: PrivilegedExecutionMethod? = null
    private var cachedProbe: ExecutionProbeResult? = null
    @Volatile
    private var configuredMethodId: String? = null

    val isAvailable: Boolean
        get() = selectedMethod() != null

    val selectedMethodId: String?
        get() = selectedMethod()?.id

    val availableMethodIds: List<String>
        get() = methods.map { it.id }

    fun setConfiguredMethodId(methodId: String?) {
        if (configuredMethodId == methodId) return
        configuredMethodId = methodId
        cachedMethod = null
        cachedProbe = null
    }

    fun autoDetectBestMethod(forceReprobe: Boolean = true): String? {
        val method = selectBestMethod(forceReprobe = forceReprobe)
        configuredMethodId = method?.id
        return method?.id
    }

    fun selectedMethod(forceReprobe: Boolean = false): PrivilegedExecutionMethod? {
        if (!forceReprobe) {
            cachedMethod?.let { return it }
        }
        cachedMethod = null
        cachedProbe = null

        val configuredId = configuredMethodId
        if (configuredId != null) {
            val configuredMethod = methods.firstOrNull { method -> method.id == configuredId }
            val configuredProbe = configuredMethod?.probe()
            if (configuredMethod != null && configuredProbe?.isAvailable == true) {
                cachedMethod = configuredMethod
                cachedProbe = configuredProbe
                return configuredMethod
            }
        }

        return selectBestMethod(forceReprobe = true)
    }

    private fun selectBestMethod(forceReprobe: Boolean): PrivilegedExecutionMethod? {
        if (!forceReprobe) {
            cachedMethod?.let { return it }
        }
        cachedMethod = null
        cachedProbe = null
        orderedMethods().forEach { method ->
            val probe = method.probe()
            if (probe.isAvailable) {
                cachedMethod = method
                cachedProbe = probe
                return method
            }
        }
        return null
    }

    private fun orderedMethods(): List<PrivilegedExecutionMethod> {
        val byId = methods.associateBy { it.id }
        return autoDetectionOrder.mapNotNull(byId::get)
    }

    fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        val method = selectedMethod()
            ?: return Result.failure(IllegalStateException("No privileged execution method available"))
        return method.executeScript(scriptName, scriptContents, captureResult)
    }

    fun readText(path: String): String? {
        return selectedMethod()?.readText(path)
    }

    fun makeReadable(path: String): Boolean {
        return selectedMethod()?.makeReadable(path) == true
    }

    companion object {
        val DEFAULT_AUTO_DETECTION_ORDER = listOf(
            "pserver-stdout",
            "root-shell",
            // No-root path via on-device wireless debugging; tried last so it
            // only activates when no privileged (root/PServer) path exists.
            "jdwp-inject",
        )

        /**
         * @param jdwpConnectionProvider supplies the on-device wireless-debugging
         *   host/port once paired. When non-null, the no-root JDWP injection
         *   method is registered (for unrooted devices). May be null (e.g. in
         *   tests) to omit it — this keeps upstream's tests, which call
         *   default(context) with no JDWP args, behaving exactly as before.
         */
        fun default(
            context: Context,
            jdwpConnectionProvider: (() -> com.aure.clustertune.jdwp.AdbConnectionInfo?)? = null,
            jdwpSharedShellProvider: (() -> com.wuyr.jdwp_injector.adb.AdbClient?)? = null,
            jdwpShellInvalidator: (() -> Unit)? = null,
            jdwpPersistentInjector: ((targetPackage: String, command: String, pid: Int, trigger: () -> Unit) -> Boolean)? = null,
        ): PrivilegedExecutionResolver {
            val rootExec = RootExec()
            val methods = mutableListOf<PrivilegedExecutionMethod>(
                PServerStdoutExecutionMethod(context, rootExec),
                RootShellExecutionMethod(),
            )
            if (jdwpConnectionProvider != null) {
                methods += com.aure.clustertune.jdwp.JdwpInjectionExecutionMethod(
                    connectionProvider = jdwpConnectionProvider,
                    sharedShellProvider = jdwpSharedShellProvider,
                    shellInvalidator = jdwpShellInvalidator,
                    persistentInjector = jdwpPersistentInjector,
                )
            }
            return PrivilegedExecutionResolver(methods)
        }
    }
}

class PServerStdoutExecutionMethod(
    private val context: Context,
    private val rootExec: PServerRootExecutor = RootExec(),
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-stdout"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val output = rootExec.executeAsRoot(
            "echo $PROBE_MARKER",
            captureOutput = true,
        ).getOrNull()?.trim()
        return if (output == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer did not return stdout",
            )
        }
    }

    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        return runCatching {
            val scriptFile = writeScriptFile(context, scriptName, scriptContents)
            rootExec.executeAsRoot(
                "sh ${shellQuote(scriptFile.absolutePath)}",
                captureOutput = captureResult,
            ).getOrThrow()
        }
    }

    override fun readText(path: String): String? {
        return rootExec.executeAsRoot(
            "cat ${shellQuote(path)} 2>/dev/null",
            captureOutput = true,
        )
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun makeReadable(path: String): Boolean {
        return rootExec.executeAsRoot(
            "chmod 444 ${shellQuote(path)} 2>/dev/null",
            captureOutput = false,
        ).isSuccess
    }
}

class PServerFileOutputExecutionMethod(
    private val context: Context?,
    private val rootExec: PServerRootExecutor = RootExec(),
    private val outputDirectory: File? = null,
    private val scriptDirectory: File? = null,
) : PrivilegedExecutionMethod {
    override val id: String = "pserver-file-output"

    override fun probe(): ExecutionProbeResult {
        if (!rootExec.pServerAvailable) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServerBinder not available",
            )
        }
        val dispatch = rootExec.executeAsRoot("true", captureOutput = false)
        return if (dispatch.isSuccess) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = false)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "PServer did not accept output-disabled execution",
            )
        }
    }

    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        if (!captureResult) {
            return runCatching {
                // Generated write scripts contain standalone commands that can be dispatched directly.
                scriptContents
                    .lineSequence()
                    .map(String::trim)
                    .filter { line -> line.isNotEmpty() && !line.startsWith('#') }
                    .forEach { command ->
                        rootExec.executeAsRoot(command, captureOutput = false).getOrThrow()
                    }
                null
            }
        }

        val operationId = UUID.randomUUID().toString()
        val scriptFile = writeFallbackScriptFile("command-$operationId.sh", scriptContents)
        val wrapperFile = writeFallbackScriptFile(
            "dispatch-$operationId.sh",
            buildString {
                val stdoutFile = outputFile("stdout-$operationId.txt")
                val stderrFile = outputFile("stderr-$operationId.txt")
                val statusFile = outputFile("status-$operationId.txt")
                val completionFile = outputFile("complete-$operationId.txt")
                appendLine("#!/system/bin/sh")
                appendLine("sh ${shellQuote(scriptFile.absolutePath)} > ${shellQuote(stdoutFile.absolutePath)} 2> ${shellQuote(stderrFile.absolutePath)}")
                appendLine("exit_code=\$?")
                appendLine("printf '%s' \"\$exit_code\" > ${shellQuote(statusFile.absolutePath)}")
                appendLine("chmod 666 ${shellQuote(stdoutFile.absolutePath)} ${shellQuote(stderrFile.absolutePath)} ${shellQuote(statusFile.absolutePath)} 2>/dev/null")
                appendLine("printf '%s' ${shellQuote(PROBE_MARKER)} > ${shellQuote(completionFile.absolutePath)}")
                appendLine("chmod 666 ${shellQuote(completionFile.absolutePath)} 2>/dev/null")
            },
        )
        val stdoutFile = outputFile("stdout-$operationId.txt")
        val stderrFile = outputFile("stderr-$operationId.txt")
        val statusFile = outputFile("status-$operationId.txt")
        val completionFile = outputFile("complete-$operationId.txt")
        val artifacts = listOf(scriptFile, wrapperFile, stdoutFile, stderrFile, statusFile, completionFile)

        return try {
            runCatching {
                rootExec.executeAsRoot(
                    "sh ${shellQuote(wrapperFile.absolutePath)}",
                    captureOutput = false,
                ).getOrThrow()
                check(waitForText(completionFile, timeoutMillis = 5_000) == PROBE_MARKER) {
                    "PServer command did not complete before the timeout"
                }
                val exitCode = statusFile.readText().trim().toIntOrNull()
                    ?: error("PServer command did not provide a valid exit status")
                val stderr = stderrFile.takeIf { it.isFile }?.readText().orEmpty().trim()
                check(exitCode == 0) {
                    if (stderr.isNotEmpty()) stderr else "PServer command failed with exit code $exitCode"
                }
                if (captureResult) {
                    stdoutFile.takeIf { it.isFile }?.readText().orEmpty()
                } else {
                    null
                }
            }
        } finally {
            artifacts.forEach(File::delete)
        }
    }

    override fun readText(path: String): String? {
        return executeScript(
            scriptName = "read-text.sh",
            scriptContents = "cat ${shellQuote(path)} 2>/dev/null",
            captureResult = true,
        ).getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun makeReadable(path: String): Boolean {
        return rootExec.executeAsRoot(
            "chmod 444 ${shellQuote(path)} 2>/dev/null",
            captureOutput = false,
        ).isSuccess
    }

    private fun outputFile(name: String): File {
        val dir = outputDirectory ?: File(appOwnedFallbackDirectory(), "root-output")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir.setExecutable(true, false)
        return File(dir, name)
    }

    private fun requireContext(): Context {
        return requireNotNull(context) { "Context is required for PServer file-output script execution" }
    }

    private fun writeFallbackScriptFile(scriptName: String, scriptContents: String): File {
        val scriptDir = scriptDirectory ?: File(appOwnedFallbackDirectory(), "root-scripts")
        if (!scriptDir.exists()) {
            scriptDir.mkdirs()
        }
        scriptDir.setReadable(true, false)
        scriptDir.setExecutable(true, false)
        val scriptFile = File(scriptDir, scriptName)
        scriptFile.writeText(scriptContents)
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)
        return scriptFile
    }

    private fun appOwnedFallbackDirectory(): File {
        return File(requireContext().filesDir, "pserver-fallback")
    }

    private fun waitForText(file: File, timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (file.isFile) {
                runCatching { file.readText() }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            try {
                Thread.sleep(25)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }
}

class RootShellExecutionMethod(
    private val runner: RootShellCommandRunner = RootShellCommandRunner(),
) : PrivilegedExecutionMethod {
    override val id: String = "root-shell"

    override fun probe(): ExecutionProbeResult {
        val result = runner.run("echo $PROBE_MARKER", timeoutSeconds = 10)
        return if (result.exitCode == 0 && result.stdout.trim() == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = result.failureMessage ?: "su did not return expected probe output",
            )
        }
    }

    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        return runner.run(scriptContents, timeoutSeconds = 30)
            .toResult()
            .map { output -> output.takeIf { captureResult } }
    }

    override fun readText(path: String): String? {
        return runner.run("cat ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10)
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun makeReadable(path: String): Boolean {
        return runner.run("chmod 444 ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10).exitCode == 0
    }
}

class ShizukuExecutionMethod(
    private val runner: ShizukuCommandRunner = ShizukuCommandRunner(),
) : PrivilegedExecutionMethod {
    override val id: String = "shizuku"

    override fun probe(): ExecutionProbeResult {
        if (!runner.isBinderAlive()) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "Shizuku binder not available",
            )
        }
        if (!runner.hasPermission()) {
            return ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = "Shizuku permission not granted",
            )
        }
        val result = runner.run("echo $PROBE_MARKER", timeoutSeconds = 10)
        return if (result.exitCode == 0 && result.stdout.trim() == PROBE_MARKER) {
            ExecutionProbeResult(isAvailable = true, supportsStdout = true)
        } else {
            ExecutionProbeResult(
                isAvailable = false,
                supportsStdout = false,
                failureReason = result.failureMessage ?: "Shizuku did not return expected probe output",
            )
        }
    }

    override fun executeScript(
        scriptName: String,
        scriptContents: String,
        captureResult: Boolean,
    ): Result<String?> {
        return runner.run(scriptContents, timeoutSeconds = 30)
            .toResult()
            .map { output -> output.takeIf { captureResult } }
    }

    override fun readText(path: String): String? {
        return runner.run("cat ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10)
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    override fun makeReadable(path: String): Boolean {
        return runner.run("chmod 444 ${shellQuote(path)} 2>/dev/null", timeoutSeconds = 10).exitCode == 0
    }
}

class RootShellCommandRunner {
    fun run(command: String, timeoutSeconds: Long): ShellCommandResult {
        return runCatchingSu(timeoutSeconds, listOf("su", "-c", command))
            .let { result ->
                if (result.shouldRetryWithUserdebugSuSyntax()) {
                    runCatchingSu(timeoutSeconds, listOf("su", "0", "sh", "-c", command))
                } else {
                    result
                }
            }
    }

    private fun runCatchingSu(
        timeoutSeconds: Long,
        invocation: List<String>,
    ): ShellCommandResult {
        return runCatching {
            val process = ProcessBuilder(invocation).start()
            process.collectOutput(timeoutSeconds)
        }.getOrElse { throwable ->
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                failureMessage = throwable.message ?: throwable::class.java.simpleName,
            )
        }
    }

    private fun ShellCommandResult.shouldRetryWithUserdebugSuSyntax(): Boolean {
        val combinedOutput = listOf(stderr, stdout, failureMessage.orEmpty()).joinToString("\n")
        return exitCode != 0 && combinedOutput.contains("invalid uid/gid '-c'")
    }
}

class ShizukuCommandRunner {
    fun isBinderAlive(): Boolean {
        return runCatching {
            shizukuClass()
                .getMethod("pingBinder")
                .invoke(null) as Boolean
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        return runCatching {
            val permission = shizukuClass()
                .getMethod("checkSelfPermission")
                .invoke(null) as Int
            permission == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestPermission(requestCode: Int): Result<Unit> {
        return runCatching {
            shizukuClass()
                .getMethod("requestPermission", Int::class.javaPrimitiveType)
                .invoke(null, requestCode)
            Unit
        }
    }

    fun run(command: String, timeoutSeconds: Long): ShellCommandResult {
        return runCatching {
            val process = newProcess(arrayOf("sh", "-c", command))
            process.collectOutput(timeoutSeconds)
        }.getOrElse { throwable ->
            ShellCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "",
                failureMessage = throwable.message ?: throwable::class.java.simpleName,
            )
        }
    }

    private fun newProcess(command: Array<String>): Process {
        val method = shizukuClass().getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    private fun shizukuClass(): Class<*> {
        return Class.forName("rikka.shizuku.Shizuku")
    }
}

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val failureMessage: String? = null,
) {
    fun toResult(): Result<String?> {
        return if (exitCode == 0) {
            Result.success(stdout.trim().takeIf { it.isNotEmpty() })
        } else {
            Result.failure(
                IllegalStateException(
                    failureMessage
                        ?: stderr.trim().takeIf { it.isNotEmpty() }
                        ?: "Command failed with exit code $exitCode",
                ),
            )
        }
    }
}

private fun Process.collectOutput(timeoutSeconds: Long): ShellCommandResult {
    val stdout = ByteArrayOutputStream()
    val stderr = ByteArrayOutputStream()
    val stdoutThread = inputStream.copyToInBackground(stdout)
    val stderrThread = errorStream.copyToInBackground(stderr)
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
    var exitCode: Int? = null
    while (System.currentTimeMillis() < deadline) {
        val value = runCatching { exitValue() }.getOrNull()
        if (value != null) {
            exitCode = value
            break
        }
        Thread.sleep(50)
    }
    if (exitCode == null) {
        destroyForcibly()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
        return ShellCommandResult(
            exitCode = -1,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            failureMessage = "Command timed out after ${timeoutSeconds}s",
        )
    }
    stdoutThread.join(1_000)
    stderrThread.join(1_000)
    return ShellCommandResult(
        exitCode = exitCode,
        stdout = stdout.toString(),
        stderr = stderr.toString(),
    )
}

private fun InputStream.copyToInBackground(output: ByteArrayOutputStream): Thread {
    return Thread {
        use { input ->
            input.copyTo(output)
        }
    }.also { thread ->
        thread.isDaemon = true
        thread.start()
    }
}

private fun writeScriptFile(
    context: Context,
    scriptName: String,
    scriptContents: String,
): File {
    val scriptDir = File(context.filesDir, "root-scripts")
    if (!scriptDir.exists()) {
        scriptDir.mkdirs()
    }
    val scriptFile = File(scriptDir, scriptName)
    scriptFile.writeText(scriptContents)
    scriptFile.setReadable(true, false)
    scriptFile.setExecutable(true, false)
    return scriptFile
}

internal fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\\''") + "'"
}
