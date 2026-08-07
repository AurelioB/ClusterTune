package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterTuneHostClientTest {
    @Test
    fun `optional host value decodes only the protocol sentinel as absent`() {
        assertNull(decodeOptionalHostValue(-1L))
        assertEquals(0L, decodeOptionalHostValue(0L))
        assertEquals(-2L, decodeOptionalHostValue(-2L))
    }
}
