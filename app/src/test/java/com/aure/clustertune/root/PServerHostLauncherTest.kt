package com.aure.clustertune.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PServerHostLauncherTest {
    @Test
    fun `probe discovers service without launching a command`() {
        val connection = Connection()
        val launcher = PServerHostLauncher { connection }
        assertTrue(launcher.pServerAvailable)
        assertEquals(emptyList<String>(), connection.commands)
    }

    @Test
    fun `missing dead and inaccessible services are unavailable`() {
        assertFalse(PServerHostLauncher { null }.pServerAvailable)
        assertFalse(PServerHostLauncher { Connection(alive = false) }.pServerAvailable)
        assertFalse(PServerHostLauncher { throw SecurityException("blocked") }.pServerAvailable)
    }

    @Test
    fun `launch resolves the service again after a successful probe`() {
        val old = Connection()
        val replacement = Connection()
        var current = old
        val launcher = PServerHostLauncher { current }
        assertTrue(launcher.pServerAvailable)
        current = replacement
        assertTrue(launcher.launchHost("sh launch-host.sh").isSuccess)
        assertEquals(emptyList<String>(), old.commands)
        assertEquals(listOf("sh launch-host.sh"), replacement.commands)
    }

    @Test
    fun `service appearing after a failed probe can launch`() {
        val connection = Connection()
        var current: PServerConnection? = null
        val launcher = PServerHostLauncher { current }
        assertFalse(launcher.pServerAvailable)
        current = connection
        assertTrue(launcher.launchHost("sh launch-host.sh").isSuccess)
        assertEquals(1, connection.commands.size)
    }

    @Test
    fun `missing or dead service fails without sending`() {
        assertTrue(PServerHostLauncher { null }.launchHost("sh launch-host.sh").isFailure)
        val dead = Connection(alive = false)
        assertTrue(PServerHostLauncher { dead }.launchHost("sh launch-host.sh").isFailure)
        assertTrue(dead.commands.isEmpty())
    }

    @Test
    fun `rejected transaction is sent only once`() {
        val connection = Connection(accepted = false)
        var discoveries = 0
        val launcher = PServerHostLauncher { discoveries++; connection }
        assertTrue(launcher.launchHost("sh launch-host.sh").isFailure)
        assertEquals(1, discoveries)
        assertEquals(1, connection.commands.size)
    }

    @Test
    fun `transport failure is preserved without retrying a possibly executed launch`() {
        val failure = IllegalStateException("connection lost")
        var sends = 0
        val connection = object : PServerConnection {
            override val alive = true
            override fun sendHostLaunch(command: String): Boolean {
                sends++
                throw failure
            }
        }
        assertSame(failure, PServerHostLauncher { connection }.launchHost("sh launch-host.sh").exceptionOrNull())
        assertEquals(1, sends)
    }

    @Test
    fun `command validation happens before service discovery`() {
        var discoveries = 0
        val launcher = PServerHostLauncher { discoveries++; Connection() }
        listOf("", "  ", "sh\u0000hidden", "x".repeat(256), "é".repeat(128)).forEach {
            assertTrue(launcher.launchHost(it).isFailure)
        }
        assertEquals(0, discoveries)
    }

    @Test
    fun `maximum size launch preserves unicode quotes and shell syntax`() {
        val connection = Connection()
        val command = "é".repeat(120) + " 'quoted' &;\n#$"
        assertEquals(255, command.toByteArray(Charsets.UTF_8).size)
        assertTrue(PServerHostLauncher { connection }.launchHost(command).isSuccess)
        assertEquals(listOf(command), connection.commands)
    }

    private class Connection(
        override val alive: Boolean = true,
        private val accepted: Boolean = true,
    ) : PServerConnection {
        val commands = mutableListOf<String>()
        override fun sendHostLaunch(command: String): Boolean {
            commands += command
            return accepted
        }
    }
}
