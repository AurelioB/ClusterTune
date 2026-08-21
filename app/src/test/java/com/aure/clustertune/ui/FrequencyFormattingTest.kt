package com.aure.clustertune.ui

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.GpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyFormattingTest {

    private val policy = CpuPolicyInfo(
        id = 0,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
        currentMaxFreq = 1_500_000,
        selectableMaxFreq = 2_000_000,
        observedMaxFreq = 2_200_000,
        minFreq = 500_000,
        supportedFrequencies = listOf(500_000, 1_000_000, 1_500_000, 2_000_000, 2_200_000),
    )

    @Test
    fun formatsFrequencyAsGhzByDefault() {
        assertEquals("1.50 GHz", formatFrequency(1_500_000, policy = policy))
    }

    @Test
    fun formatsFrequencyAsPercentOfSelectableMaxWhenEnabled() {
        assertEquals(
            "75%",
            formatFrequency(
                valueKhz = 1_500_000,
                policy = policy,
                displayAsPercent = true,
            ),
        )
    }

    @Test
    fun keepsBoostMarkerInPercentMode() {
        assertEquals(
            "110%+",
            formatFrequency(
                valueKhz = 2_200_000,
                boosted = true,
                policy = policy,
                displayAsPercent = true,
            ),
        )
    }

    @Test fun targetAtSelectableMaxIsStock() {
        assertEquals("Stock", formatTargetFrequency(2_000_000, policy))
    }

    @Test fun currentAtSelectableMaxIsNumeric() {
        assertEquals(
            "2.00 GHz",
            formatFrequency(2_000_000, policy = policy, showStockLabel = false),
        )
    }

    @Test fun targetAtHiddenObservedMaxIsStock() {
        assertEquals("Stock", formatTargetFrequency(2_200_000, policy))
    }

    @Test fun targetBelowSelectableMaxKeepsFrequency() {
        assertEquals("1.50 GHz", formatTargetFrequency(1_500_000, policy))
    }

    @Test fun stockMetadataUsesNumericCeilingEvenAtSelectableMax() {
        assertEquals(
            "2.00 GHz",
            formatFrequency(2_000_000, policy = policy, showStockLabel = false),
        )
        assertEquals(
            "2.20 GHz+",
            formatFrequency(2_200_000, boosted = policy.isBoosted(2_200_000), policy = policy, showStockLabel = false),
        )
    }

    @Test fun gpuAtOrAboveSelectableMaxIsStock() {
        val gpu = GpuPolicyInfo("/gpu", "/gpu/max", selectableMaxFrequencyHz = 600_000_000, observedMaxFrequencyHz = 800_000_000, currentMaxFrequencyHz = 600_000_000)
        assertEquals("Stock", formatGpuFrequency(600_000_000, gpu))
        assertEquals("Stock", formatGpuFrequency(800_000_000, gpu))
        assertEquals("400 MHz", formatGpuFrequency(400_000_000, gpu))
    }

    @Test fun currentGpuAtSelectableMaxIsNumeric() {
        assertEquals("600 MHz", formatGpuFrequency(600_000_000))
    }
}
