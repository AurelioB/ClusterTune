package com.aure.clustertune.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualApplyCoalescingTest {
    @Test
    fun newerManualRequestSupersedesOlderToken() {
        val older = PerformanceRepository.allocateManualRequestToken()
        val newest = PerformanceRepository.allocateManualRequestToken()

        assertFalse(PerformanceRepository.isManualRequestCurrent(older))
        assertTrue(PerformanceRepository.isManualRequestCurrent(newest))
    }
}
