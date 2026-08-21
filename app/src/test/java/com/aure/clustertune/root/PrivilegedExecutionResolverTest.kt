package com.aure.clustertune.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull

class PrivilegedExecutionResolverTest {
    @Test
    fun `auto detection probes in configured order`() {
        val probes = mutableListOf<String>()
        val pserver = FakeMethod("pserver-stdout", available = false) { probes += it }
        val root = FakeMethod("root-shell", available = true) { probes += it }
        val resolver = PrivilegedExecutionResolver(listOf(root, pserver))

        assertEquals("root-shell", resolver.autoDetectBestMethod())
        assertEquals(listOf("pserver-stdout", "root-shell"), probes)
    }

    @Test
    fun `launch is forwarded only to selected lifecycle method`() {
        val pserver = FakeMethod("pserver-stdout", available = true)
        val root = FakeMethod("root-shell", available = true)
        val resolver = PrivilegedExecutionResolver(listOf(pserver, root))
        val snapshot = resolver.selectionSnapshot()
        val request = HostLaunchRequest("/dex", "launch.sh")

        assertTrue(resolver.launchHost(snapshot, request).isSuccess)
        assertEquals(1, pserver.launchCount)
        assertEquals(0, root.launchCount)
    }

    @Test
    fun `changed selection rejects stale lifecycle launch`() {
        val pserver = FakeMethod("pserver-stdout", available = true)
        val root = FakeMethod("root-shell", available = true)
        val resolver = PrivilegedExecutionResolver(listOf(pserver, root))
        val snapshot = resolver.selectionSnapshot()
        resolver.setConfiguredMethodId("root-shell")

        assertFalse(resolver.launchHost(snapshot, HostLaunchRequest("/dex", "launch.sh")).isSuccess)
    }

    @Test
    fun `pserver probe only checks binder availability`() {
        val executor = RecordingPServer(pServerAvailable = true)
        assertTrue(PServerExecutionMethod(executor).probe().isAvailable)
        assertEquals(0, executor.launches)
    }

    @Test
    fun `pserver launch forwards only short launcher envelope`() {
        val executor = RecordingPServer(pServerAvailable = true)
        assertTrue(PServerExecutionMethod(executor)
            .launchHost(HostLaunchRequest("/data/cache/ct host", "launch-host.sh")).isSuccess)
        assertEquals("cd '/data/cache/ct host' && sh 'launch-host.sh'", executor.command)
        assertFalse(executor.command!!.contains("app_process"))
        assertFalse(executor.command!!.contains("/sys/"))
    }

    private class FakeMethod(
        override val id: String,
        private val available: Boolean,
        private val onProbe: (String) -> Unit = {},
    ) : PrivilegedExecutionMethod {
        var launchCount = 0
        override fun probe() = ExecutionProbeResult(available).also { onProbe(id) }
        override fun launchHost(request: HostLaunchRequest): Result<Unit> {
            launchCount++
            return Result.success(Unit)
        }
    }

    @Test
    fun `pserver present but not callable is not selected`() {
        // Odin 2 Mini: PServerBinder is registered, so pServerAvailable is true,
        // but every transaction is denied by SELinux. Selecting it here would
        // shadow the working method and leave the host unreachable.
        val pserver = PServerExecutionMethod(RecordingPServer(pServerAvailable = true, callAllowed = false))
        val resolver = PrivilegedExecutionResolver(
            listOf(pserver),
            autoDetectionOrder = listOf("pserver-stdout"),
        )
        assertNull(resolver.autoDetectBestMethod(forceReprobe = true))
    }

    private class RecordingPServer(
        override val pServerAvailable: Boolean,
        /**
         * Whether a transaction is actually permitted. Defaults to true so
         * existing cases are unchanged; the Odin 2 Mini is the false case,
         * where the binder exists but SELinux refuses every call.
         */
        private val callAllowed: Boolean = true,
    ) : PServerHostExecutor {
        var launches = 0
        var command: String? = null
        override fun launchHost(command: String): Result<Unit> {
            launches++
            this.command = command
            return Result.success(Unit)
        }

        override fun verify(): Result<Unit> = if (callAllowed) {
            Result.success(Unit)
        } else {
            Result.failure(SecurityException("FAILED BINDER TRANSACTION"))
        }
    }
}
