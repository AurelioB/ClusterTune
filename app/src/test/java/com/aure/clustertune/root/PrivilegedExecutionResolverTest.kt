package com.aure.clustertune.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedExecutionResolverTest {

    @Test
    fun `selects first available execution method`() {
        val unavailable = FakeExecutionMethod(
            id = "unavailable",
            probeResult = ExecutionProbeResult(false, false),
        )
        val pserver = FakeExecutionMethod(
            id = "pserver-stdout",
            probeResult = ExecutionProbeResult(true, true),
            scriptOutput = "ok",
            reads = mapOf("/sys/test" to "123"),
        )
        val fallback = FakeExecutionMethod(
            id = "pserver-file-output",
            probeResult = ExecutionProbeResult(true, false),
        )
        val resolver = PrivilegedExecutionResolver(
            methods = listOf(unavailable, pserver, fallback),
            autoDetectionOrder = listOf("unavailable", "pserver-stdout", "pserver-file-output"),
        )

        assertTrue(resolver.isAvailable)
        assertEquals("pserver-stdout", resolver.selectedMethodId)
        assertEquals("123", resolver.readText("/sys/test"))
        assertEquals("ok", resolver.executeScript("apply.sh", "echo ok", captureResult = true).getOrThrow())
        assertEquals(1, unavailable.probeCount)
        assertEquals(1, pserver.probeCount)
        assertEquals(0, fallback.probeCount)
    }

    @Test
    fun `falls back when stdout method probe fails`() {
        val stdout = FakeExecutionMethod(
            id = "pserver-stdout",
            probeResult = ExecutionProbeResult(false, false, "no stdout"),
        )
        val fileOutput = FakeExecutionMethod(
            id = "pserver-file-output",
            probeResult = ExecutionProbeResult(true, false),
            reads = mapOf("/protected" to "value"),
        )
        val resolver = PrivilegedExecutionResolver(listOf(stdout, fileOutput))

        assertTrue(resolver.isAvailable)
        assertEquals("pserver-file-output", resolver.selectedMethodId)
        assertEquals("value", resolver.readText("/protected"))
    }

    @Test
    fun `reports unavailable when all methods fail`() {
        val resolver = PrivilegedExecutionResolver(
            listOf(
                FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(false, false)),
                FakeExecutionMethod("pserver-file-output", ExecutionProbeResult(false, false)),
            ),
        )

        assertFalse(resolver.isAvailable)
        assertNull(resolver.selectedMethodId)
        assertTrue(resolver.executeScript("apply.sh", "echo ok", captureResult = false).isFailure)
    }

    @Test
    fun `shell quotes paths with single quotes`() {
        assertEquals("'/sys/path'", shellQuote("/sys/path"))
        assertEquals("'/data/a'\\''b'", shellQuote("/data/a'b"))
    }

    @Test
    fun `configured method wins over auto detection order`() {
        val pserver = FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(true, true))
        val shizuku = FakeExecutionMethod("shizuku", ExecutionProbeResult(true, true))
        val resolver = PrivilegedExecutionResolver(listOf(pserver, shizuku))

        resolver.setConfiguredMethodId("shizuku")

        assertEquals("shizuku", resolver.selectedMethodId)
        assertEquals(0, pserver.probeCount)
        assertEquals(1, shizuku.probeCount)
    }

    @Test
    fun `auto detect skips shizuku and persists root when pserver methods are unavailable`() {
        val pserver = FakeExecutionMethod("pserver-stdout", ExecutionProbeResult(false, false))
        val fileOutput = FakeExecutionMethod("pserver-file-output", ExecutionProbeResult(false, false))
        val shizuku = FakeExecutionMethod("shizuku", ExecutionProbeResult(true, true))
        val rootShell = FakeExecutionMethod("root-shell", ExecutionProbeResult(true, true))
        val resolver = PrivilegedExecutionResolver(listOf(rootShell, shizuku, fileOutput, pserver))

        assertEquals("root-shell", resolver.autoDetectBestMethod())
        assertEquals("root-shell", resolver.selectedMethodId)
        assertEquals(1, pserver.probeCount)
        assertEquals(1, fileOutput.probeCount)
        assertEquals(1, rootShell.probeCount)
        assertEquals(0, shizuku.probeCount)
    }

    @Test
    fun `default auto detection prefers stdout then file output then root`() {
        val probeOrder = mutableListOf<String>()
        val stdout = FakeExecutionMethod(
            "pserver-stdout",
            ExecutionProbeResult(false, false),
            onProbe = { probeOrder += "pserver-stdout" },
        )
        val fileOutput = FakeExecutionMethod(
            "pserver-file-output",
            ExecutionProbeResult(false, false),
            onProbe = { probeOrder += "pserver-file-output" },
        )
        val rootShell = FakeExecutionMethod(
            "root-shell",
            ExecutionProbeResult(true, true),
            onProbe = { probeOrder += "root-shell" },
        )
        val resolver = PrivilegedExecutionResolver(listOf(rootShell, fileOutput, stdout))

        assertEquals("root-shell", resolver.autoDetectBestMethod())
        assertEquals(
            listOf("pserver-stdout", "pserver-file-output", "root-shell"),
            probeOrder,
        )
    }

    @Test
    fun `pserver file output read uses script internal file write when stdout is empty`() {
        val outputDir = temporaryDirectory()
        val scriptDir = temporaryDirectory()
        val sourceFile = File(outputDir, "sys-value.txt").also { it.writeText("24") }
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = ShellBackedNoStdoutPServerExecutor(),
            outputDirectory = outputDir,
            scriptDirectory = scriptDir,
        )

        assertEquals("24", method.readText(sourceFile.absolutePath))
    }

    @Test
    fun `pserver file output dispatches with output capture disabled`() {
        val outputDir = temporaryDirectory()
        val scriptDir = temporaryDirectory()
        val executor = ShellBackedNoStdoutPServerExecutor()
        val sourceFile = File(outputDir, "sys-value.txt").also { it.writeText("42") }
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = executor,
            outputDirectory = outputDir,
            scriptDirectory = scriptDir,
        )

        assertTrue(method.probe().isAvailable)
        assertEquals("42", method.readText(sourceFile.absolutePath))
        method.executeScript("apply.sh", "true", captureResult = false).getOrThrow()

        assertTrue(executor.captureOutputArguments.isNotEmpty())
        assertTrue(executor.captureOutputArguments.all { captureOutput -> !captureOutput })
    }

    @Test
    fun `pserver file output uses a distinct bridge file for each read`() {
        val outputDir = temporaryDirectory()
        val executor = ShellBackedNoStdoutPServerExecutor()
        val sourceFile = File(outputDir, "sys-value.txt").also { it.writeText("7") }
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = executor,
            outputDirectory = outputDir,
            scriptDirectory = temporaryDirectory(),
        )

        assertEquals("7", method.readText(sourceFile.absolutePath))
        assertEquals("7", method.readText(sourceFile.absolutePath))

        val bridgePaths = executor.commands
            .mapNotNull { command -> Regex("dispatch-([0-9a-f-]+)\\.sh").find(command)?.value }
        assertEquals(2, bridgePaths.size)
        assertEquals(2, bridgePaths.toSet().size)
    }

    @Test
    fun `pserver file output bridges captured script output`() {
        val outputDir = temporaryDirectory()
        val scriptDir = temporaryDirectory()
        val sideEffect = File(outputDir, "side-effect.txt")
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = ShellBackedNoStdoutPServerExecutor(),
            outputDirectory = outputDir,
            scriptDirectory = scriptDir,
        )

        val output = method.executeScript(
            "apply.sh",
            "echo landed > ${shellQuote(sideEffect.absolutePath)}\necho log-line\n",
            captureResult = true,
        ).getOrThrow()

        assertEquals("landed", sideEffect.readText().trim())
        assertEquals("log-line\n", output)
    }

    private class FakeExecutionMethod(
        override val id: String,
        private val probeResult: ExecutionProbeResult,
        private val scriptOutput: String? = null,
        private val reads: Map<String, String> = emptyMap(),
        private val onProbe: () -> Unit = {},
    ) : PrivilegedExecutionMethod {
        var probeCount = 0
            private set

        override fun probe(): ExecutionProbeResult {
            probeCount += 1
            onProbe()
            return probeResult
        }

        override fun executeScript(
            scriptName: String,
            scriptContents: String,
            captureResult: Boolean,
        ): Result<String?> {
            return Result.success(scriptOutput)
        }

        override fun readText(path: String): String? {
            return reads[path]
        }
    }

    private class ShellBackedNoStdoutPServerExecutor : PServerRootExecutor {
        override val pServerAvailable: Boolean = true
        val commands = mutableListOf<String>()
        val captureOutputArguments = mutableListOf<Boolean>()

        override fun executeAsRoot(cmd: String): Result<String?> {
            return executeAsRoot(cmd, captureOutput = true)
        }

        override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
            commands += cmd
            captureOutputArguments += captureOutput
            return runShell(cmd)
        }

        private fun runShell(cmd: String): Result<String?> {
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

    private fun temporaryDirectory(): File {
        return File(System.getProperty("java.io.tmpdir"), "clustertune-test-${System.nanoTime()}")
            .also { it.mkdirs() }
    }
}
