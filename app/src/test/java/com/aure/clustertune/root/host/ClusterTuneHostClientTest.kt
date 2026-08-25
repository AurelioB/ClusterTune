package com.aure.clustertune.root.host

import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTuneHostClientTest {
    @Test
    fun `optional host value decodes only the protocol sentinel as absent`() {
        assertNull(decodeOptionalHostValue(-1L))
        assertEquals(0L, decodeOptionalHostValue(0L))
        assertEquals(-2L, decodeOptionalHostValue(-2L))
    }

    @Test
    fun `host matching uses effective fallback instead of unavailable configuration`() {
        val resolver = PrivilegedExecutionResolver(
            listOf(
                ProbeMethod("pserver-stdout", available = false),
                ProbeMethod("root-shell", available = true),
            ),
        )
        resolver.setConfiguredMethodId("pserver-stdout")

        val selection = resolver.selectionSnapshot()

        assertEquals("pserver-stdout", resolver.configuredMethodIdSnapshot)
        assertEquals("root-shell", selection.methodId)
        assertTrue(selection.matchesHostMethod("root-shell"))
        assertFalse(selection.matchesHostMethod("pserver-stdout"))
    }

    private class ProbeMethod(
        override val id: String,
        private val available: Boolean,
    ) : PrivilegedExecutionMethod {
        override fun probe() = ExecutionProbeResult(available)
        override fun launchHost(request: HostLaunchRequest) = Result.success(Unit)
    }
}
