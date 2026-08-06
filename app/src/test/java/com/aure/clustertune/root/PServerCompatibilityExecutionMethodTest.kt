package com.aure.clustertune.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PServerCompatibilityExecutionMethodTest {

    @Test
    fun `probe uses harmless output-disabled dispatch without storage bridge`() {
        val parent = temporaryDirectory()
        val outputDirectory = File(parent, "output-not-created")
        val scriptDirectory = File(parent, "scripts-not-created")
        val executor = RecordingExecutor()
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = executor,
            outputDirectory = outputDirectory,
            scriptDirectory = scriptDirectory,
        )

        val result = method.probe()

        assertTrue(result.isAvailable)
        assertFalse(result.supportsStdout)
        assertEquals(listOf("true"), executor.commands)
        assertEquals(listOf(false), executor.captureOutputArguments)
        assertFalse(outputDirectory.exists())
        assertFalse(scriptDirectory.exists())
    }

    @Test
    fun `script without result dispatches standalone commands and creates no bridge artifacts`() {
        val outputDirectory = temporaryDirectory()
        val scriptDirectory = temporaryDirectory()
        val executor = RecordingExecutor()
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = executor,
            outputDirectory = outputDirectory,
            scriptDirectory = scriptDirectory,
        )

        val result = method.executeScript(
            scriptName = "apply.sh",
            scriptContents = """
                # generated frequency update

                chmod 666 /sys/example/scaling_max_freq
                echo 1200000 > /sys/example/scaling_max_freq
                  # generated permission restore
                chmod 444 /sys/example/scaling_max_freq
            """.trimIndent(),
            captureResult = false,
        )

        assertNull(result.getOrThrow())
        assertEquals(
            listOf(
                "chmod 666 /sys/example/scaling_max_freq",
                "echo 1200000 > /sys/example/scaling_max_freq",
                "chmod 444 /sys/example/scaling_max_freq",
            ),
            executor.commands,
        )
        assertEquals(listOf(false, false, false), executor.captureOutputArguments)
        assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(scriptDirectory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `script requiring result still uses bridge and returns output`() {
        val outputDirectory = temporaryDirectory()
        val scriptDirectory = temporaryDirectory()
        val executor = ShellBackedExecutor()
        val method = PServerFileOutputExecutionMethod(
            context = null,
            rootExec = executor,
            outputDirectory = outputDirectory,
            scriptDirectory = scriptDirectory,
        )

        val result = method.executeScript(
            scriptName = "list.sh",
            scriptContents = "printf '%s\\n' policy0 policy7",
            captureResult = true,
        )

        assertEquals("policy0\npolicy7\n", result.getOrThrow())
        assertEquals(listOf(false), executor.captureOutputArguments)
        assertTrue(executor.commands.single().contains("dispatch-"))
        assertTrue(outputDirectory.listFiles().orEmpty().isEmpty())
        assertTrue(scriptDirectory.listFiles().orEmpty().isEmpty())
    }

    private class RecordingExecutor : PServerRootExecutor {
        override val pServerAvailable = true
        val commands = mutableListOf<String>()
        val captureOutputArguments = mutableListOf<Boolean>()

        override fun executeAsRoot(cmd: String): Result<String?> {
            error("Operation must explicitly declare whether output is captured")
        }

        override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
            commands += cmd
            captureOutputArguments += captureOutput
            return Result.success(null)
        }
    }

    private class ShellBackedExecutor : PServerRootExecutor {
        override val pServerAvailable = true
        val commands = mutableListOf<String>()
        val captureOutputArguments = mutableListOf<Boolean>()

        override fun executeAsRoot(cmd: String): Result<String?> {
            error("Operation must explicitly declare whether output is captured")
        }

        override fun executeAsRoot(cmd: String, captureOutput: Boolean): Result<String?> {
            commands += cmd
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

    private fun temporaryDirectory(): File {
        return File(System.getProperty("java.io.tmpdir"), "clustertune-compat-${System.nanoTime()}")
            .also { it.mkdirs() }
    }
}
