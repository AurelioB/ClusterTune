package com.aure.clustertune.root.host

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.root.PrivilegedExecutionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootHostHandoffInstrumentationTest {
    @Test
    fun rootHostBinderHandoff_roundTripsThroughProductionClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = PrivilegedExecutionResolver.default(context)
        val method = resolver.autoDetectBestMethod(forceReprobe = true)
        assumeNotNull("root-shell unavailable", method)
        org.junit.Assume.assumeTrue(method == "root-shell")

        val client = ClusterTuneHostClient(context, resolver)
        try {
            assertTrue("host start failed", client.ensureStarted(5_000).isSuccess)
            assertEquals("root-shell", client.selectedMethodId)
            val snapshot = client.readSnapshot().getOrThrow()
            assertTrue(snapshot.capabilities.cpus.isNotEmpty())
        } finally {
            client.stop()
        }
    }
}
