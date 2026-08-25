package com.aure.clustertune.root.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTuneHostLifecycleTest {
    @Test
    fun `initial handoff timeout applies only before the first lease`() {
        val lifecycle = ClusterTuneHostEntry.HostProcessLifecycle()

        assertFalse(lifecycle.initialHandoffExpired(false, 99L, 100L))
        assertTrue(lifecycle.initialHandoffExpired(false, 100L, 100L))
        assertFalse(lifecycle.initialHandoffExpired(true, 100L, 100L))

        lifecycle.leaseEstablished()
        lifecycle.leaseLost()

        assertFalse(lifecycle.initialHandoffExpired(false, 1_000L, 100L))
        assertTrue(lifecycle.isWaitingForLeaseLossRestoration(false))
    }

    @Test
    fun `lost lease cannot stop host until restoration is complete`() {
        val lifecycle = ClusterTuneHostEntry.HostProcessLifecycle()
        lifecycle.leaseEstablished()
        lifecycle.leaseLost()

        assertFalse(lifecycle.canFinishLeaseLoss(false, false))
        assertFalse(lifecycle.isStopping)
        assertTrue(lifecycle.canFinishLeaseLoss(false, true))

        lifecycle.finishStopping()

        assertTrue(lifecycle.isStopping)
        assertFalse(lifecycle.isWaitingForLeaseLossRestoration(false))
    }

    @Test
    fun `replacement lease cancels orphan shutdown while pending restore remains recoverable`() {
        val lifecycle = ClusterTuneHostEntry.HostProcessLifecycle()
        lifecycle.leaseEstablished()
        lifecycle.leaseLost()

        assertTrue(lifecycle.isWaitingForLeaseLossRestoration(false))
        assertFalse(lifecycle.canFinishLeaseLoss(true, true))

        lifecycle.leaseEstablished()

        assertFalse(lifecycle.isWaitingForLeaseLossRestoration(true))
        assertFalse(lifecycle.canFinishLeaseLoss(false, true))
        assertFalse(lifecycle.isStopping)
    }
}
