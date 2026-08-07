package com.aure.clustertune.root

import android.content.ContextWrapper
import com.aure.clustertune.data.CpuPolicyDetector
import com.aure.clustertune.data.PrivilegedSysfsLister
import com.aure.clustertune.data.PrivilegedSysfsReader
import com.aure.clustertune.data.SysfsFileSystem
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionOperationRoutingTest {

    @Test
    fun `policy listing requests captured output and returns it`() {
        val method = RecordingMethod(
            scriptResult = Result.success(
                "/sys/devices/system/cpu/cpufreq/policy0\n" +
                    "/sys/devices/system/cpu/cpufreq/policy7\n",
            ),
        )
        val resolver = resolver(method)

        val policies = ExecutionMethodSysfsLister(resolver).listChildrenWithPrefix(
            "/sys/devices/system/cpu/cpufreq",
            "policy",
        )

        assertEquals(
            listOf(
                "/sys/devices/system/cpu/cpufreq/policy0",
                "/sys/devices/system/cpu/cpufreq/policy7",
            ),
            policies,
        )
        assertEquals(listOf(true), method.captureResultArguments)
    }

    @Test
    fun `profile apply requires captured completion marker`() = runTest {
        val method = RecordingMethod(
            scriptResult = Result.success(PerformanceCommandBuilder.COMPLETION_MARKER),
        )
        val resolver = resolver(method)
        val runner = RootCommandRunner(
            context = ContextWrapper(null),
            executionResolver = resolver,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertEquals(
            PerformanceCommandBuilder.COMPLETION_MARKER,
            runner.executeScript("echo 1200000 > /sys/example").getOrThrow(),
        )
        assertEquals(listOf(true), method.captureResultArguments)
    }

    @Test
    fun `profile apply fails when completion marker is missing`() = runTest {
        val method = RecordingMethod(scriptResult = Result.success(""))
        val runner = RootCommandRunner(
            context = ContextWrapper(null),
            executionResolver = resolver(method),
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(runner.executeScript("false").isFailure)
    }

    @Test
    fun `direct PServer probe and protected read request output`() {
        val executor = RecordingPServerExecutor(
            results = ArrayDeque(
                listOf(
                    Result.success("clustertune-exec-probe-ok\n"),
                    Result.success("1800000\n"),
                ),
            ),
        )
        val method = PServerStdoutExecutionMethod(ContextWrapper(null), executor)

        assertTrue(method.probe().isAvailable)
        assertEquals("1800000", method.readText("/sys/example/scaling_max_freq"))
        assertEquals(listOf(true, true), executor.captureOutputArguments)
    }

    @Test
    fun `direct PServer profile apply dispatches without output capture`() {
        val scriptDirectory = temporaryDirectory()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = scriptDirectory
        }
        val executor = ShellBackedExecutor()
        val method = PServerStdoutExecutionMethod(context, executor)

        assertNull(
            method.executeScript(
                scriptName = "apply.sh",
                scriptContents = "printf '%s' applied",
                captureResult = false,
            ).getOrThrow(),
        )
        assertEquals(listOf(false), executor.captureOutputArguments)
    }

    @Test
    fun `direct PServer script returns captured output after successful completion`() {
        val directory = temporaryDirectory()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        }
        val method = PServerStdoutExecutionMethod(context, ShellBackedExecutor())

        assertEquals(
            "applied\n",
            method.executeScript(
                scriptName = "apply.sh",
                scriptContents = "printf '%s\\n' applied",
                captureResult = true,
            ).getOrThrow(),
        )
    }

    @Test
    fun `direct PServer script reports nonzero status`() {
        val directory = temporaryDirectory()
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = directory
        }
        val method = PServerStdoutExecutionMethod(context, ShellBackedExecutor())

        val result = method.executeScript(
            scriptName = "apply.sh",
            scriptContents = "printf '%s' failed >&2; exit 7",
            captureResult = true,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("failed"))
    }

    @Test
    fun `accessible policy data never invokes privileged fallbacks`() {
        val root = "/sys/devices/system/cpu/cpufreq"
        val policy = "$root/policy0"
        val fileSystem = MapSysfsFileSystem(
            directories = listOf(policy),
            files = mapOf(
                "$policy/scaling_available_frequencies" to "300000 1200000 2400000",
                "$policy/affected_cpus" to "0 1 2 3",
                "$policy/cpuinfo_max_freq" to "2400000",
                "$policy/cpuinfo_min_freq" to "300000",
                "$policy/scaling_max_freq" to "1200000",
                "$policy/stats/time_in_state" to "300000 1\n2400000 1",
                "$policy/scaling_min_freq" to "300000",
            ),
        )
        val reader = CountingPrivilegedReader()
        val lister = CountingPrivilegedLister()

        val policies = CpuPolicyDetector(
            fileSystem = fileSystem,
            privilegedReader = reader,
            privilegedLister = lister,
            policyRoot = root,
        ).detectPolicies()

        assertEquals(listOf(0), policies.map { it.id })
        assertEquals(0, reader.readCount)
        assertEquals(0, lister.callCount)
    }

    private fun resolver(method: PrivilegedExecutionMethod): PrivilegedExecutionResolver {
        return PrivilegedExecutionResolver(
            methods = listOf(method),
            autoDetectionOrder = listOf(method.id),
        )
    }

    private class RecordingMethod(
        private val scriptResult: Result<String?>,
    ) : PrivilegedExecutionMethod {
        override val id = "recording"
        val captureResultArguments = mutableListOf<Boolean>()

        override fun probe() = ExecutionProbeResult(isAvailable = true, supportsStdout = true)

        override fun executeScript(
            scriptName: String,
            scriptContents: String,
            captureResult: Boolean,
        ): Result<String?> {
            captureResultArguments += captureResult
            return scriptResult
        }

        override fun readText(path: String): String? = null
    }

    private class RecordingPServerExecutor(
        private val results: ArrayDeque<Result<String?>>,
    ) : PServerRootExecutor {
        override val pServerAvailable = true
        val captureOutputArguments = mutableListOf<Boolean>()

        override fun executeAsRoot(cmd: String): Result<String?> {
            error("Operation must explicitly declare whether output is captured")
        }

        override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
            captureOutputArguments += captureOutput
            return results.removeFirst()
        }
    }

    private class ShellBackedExecutor : PServerRootExecutor {
        override val pServerAvailable = true
        val captureOutputArguments = mutableListOf<Boolean>()

        override fun executeAsRoot(cmd: String): Result<String?> {
            error("Operation must explicitly declare whether output is captured")
        }

        override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
            captureOutputArguments += captureOutput
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            return if (exitCode == 0) {
                Result.success(output.takeIf { it.isNotEmpty() })
            } else {
                Result.failure(IllegalStateException("shell exited $exitCode"))
            }
        }
    }

    private class MapSysfsFileSystem(
        private val directories: List<String>,
        private val files: Map<String, String>,
    ) : SysfsFileSystem {
        override fun listPolicyDirectories(root: String): List<String> = directories
        override fun readText(path: String): String? = files[path]
    }

    private class CountingPrivilegedReader : PrivilegedSysfsReader {
        var readCount = 0
            private set

        override fun readText(path: String): String? {
            readCount += 1
            return null
        }

    }

    private class CountingPrivilegedLister : PrivilegedSysfsLister {
        var callCount = 0
            private set

        override fun listChildrenWithPrefix(directoryPath: String, prefix: String): List<String>? {
            callCount += 1
            return null
        }
    }

    private fun temporaryDirectory(): File {
        return File(System.getProperty("java.io.tmpdir"), "clustertune-routing-${System.nanoTime()}")
            .also { it.mkdirs() }
    }
}
