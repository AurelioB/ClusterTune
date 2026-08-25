package com.aure.clustertune.root

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `unavailable configured method reports and launches effective fallback`() {
        val pserver = FakeMethod("pserver-stdout", available = false)
        val root = FakeMethod("root-shell", available = true)
        val resolver = PrivilegedExecutionResolver(listOf(pserver, root))
        resolver.setConfiguredMethodId("pserver-stdout")

        assertEquals("pserver-stdout", resolver.configuredMethodIdSnapshot)
        assertEquals("root-shell", resolver.selectedMethodId)
        val snapshot = resolver.selectionSnapshot()
        assertEquals("root-shell", snapshot.methodId)

        assertTrue(resolver.launchHost(snapshot, HostLaunchRequest("/dex", "launch.sh")).isSuccess)
        assertEquals(0, pserver.launchCount)
        assertEquals(1, root.launchCount)
    }

    @Test
    fun `stable selection serializes configuration changes with host handoff`() {
        val pserver = FakeMethod("pserver-stdout", available = true)
        val root = FakeMethod("root-shell", available = true)
        val resolver = PrivilegedExecutionResolver(listOf(pserver, root))
        val handoffEntered = CountDownLatch(1)
        val releaseHandoff = CountDownLatch(1)
        val changeAttempted = CountDownLatch(1)
        val changeCompleted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val handoff = executor.submit {
                resolver.withStableSelection { selection ->
                    assertEquals("pserver-stdout", selection.methodId)
                    handoffEntered.countDown()
                    assertTrue(releaseHandoff.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(handoffEntered.await(2, TimeUnit.SECONDS))
            executor.submit {
                changeAttempted.countDown()
                resolver.setConfiguredMethodId("root-shell")
                changeCompleted.countDown()
            }
            assertTrue(changeAttempted.await(2, TimeUnit.SECONDS))
            assertFalse(changeCompleted.await(100, TimeUnit.MILLISECONDS))

            releaseHandoff.countDown()
            handoff.get(2, TimeUnit.SECONDS)
            assertTrue(changeCompleted.await(2, TimeUnit.SECONDS))
            assertEquals("root-shell", resolver.selectedMethodId)
        } finally {
            releaseHandoff.countDown()
            executor.shutdownNow()
        }
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

    private class RecordingPServer(override val pServerAvailable: Boolean) : PServerHostExecutor {
        var launches = 0
        var command: String? = null
        override fun launchHost(command: String): Result<Unit> {
            launches++
            this.command = command
            return Result.success(Unit)
        }
    }
}
