package com.aure.clustertune.root.host

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLaunchScriptTest {
    @Test
    fun `launcher detaches host session and preserves quoted arguments and classpath`() {
        val directory = Files.createTempDirectory("ct-host-launch").toFile()
        try {
            val fixture = directory.resolve("fixture.sh")
            fixture.writeText("""
                #!/bin/sh
                printf '%s\n' "${'$'}CLASSPATH" "${'$'}@" > result
                ps -o sid= -p ${'$'}${'$'} > child-session
                if read line; then echo unexpected-input; else echo detached; fi > stdin-state
                touch done
            """.trimIndent())
            fixture.setExecutable(true)
            val classpath = "/path with spaces/classes.dex:/path's/classes2.dex"
            val args = listOf("HostEntry", "service'quoted", "literal ${'$'}(false)")
            val script = hostLaunchScript(args, classpath)
                .replace("/system/bin/app_process /system/bin", fixture.absolutePath)
            directory.resolve("launch.sh").writeText(script)
            val parent = ProcessBuilder("sh", "-c", "ps -o sid= -p ${'$'}${'$'} > parent-session; sh launch.sh")
                .directory(directory).start()
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, parent.exitValue())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!directory.resolve("done").exists() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(directory.resolve("done").exists())
            assertEquals(listOf(classpath) + args, directory.resolve("result").readLines())
            assertEquals("detached", directory.resolve("stdin-state").readText().trim())
            assertNotEquals(directory.resolve("parent-session").readText().trim(), directory.resolve("child-session").readText().trim())
        } finally {
            directory.deleteRecursively()
        }
    }
}
