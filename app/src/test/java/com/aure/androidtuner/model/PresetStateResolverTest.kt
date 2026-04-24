package com.aure.androidtuner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStateResolverTest {

    @Test
    fun `resolves stock as virtual preset`() {
        val policies = listOf(
            policy(id = 0, current = 3_532_800, stock = 3_532_800, supported = listOf(1_785_600, 3_532_800)),
            policy(id = 6, current = 4_320_000, stock = 4_320_000, supported = listOf(1_958_400, 4_320_000)),
        )

        val state = PresetStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = policies.associate { it.id to it.currentMaxFreq },
                currentValues = policies.associate { it.id to it.stockMaxFreq },
            ),
        )

        assertEquals(PresetStateResolver.STOCK_PROFILE_ID, state.activeDisplayProfileId)
        assertEquals("Stock", state.activeDisplayProfileName)
        assertEquals(PresetStateResolver.STOCK_PROFILE_ID, state.selectedDisplayProfileId)
    }

    @Test
    fun `resolves manual when values do not match a preset`() {
        val policies = listOf(
            policy(id = 0, current = 2_500_000, stock = 3_532_800, supported = listOf(1_785_600, 2_500_000, 3_532_800)),
        )

        val state = PresetStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = mapOf(0 to 2_500_000),
                currentValues = mapOf(0 to 2_500_000),
            ),
        )

        assertTrue(state.isManualActive)
        assertTrue(state.isManualSelection)
        assertEquals("Manual", state.activeDisplayProfileName)
    }

    private fun policy(id: Int, current: Int, stock: Int, supported: List<Int>) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy$id",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        currentMaxFreq = current,
        stockMaxFreq = stock,
        minFreq = supported.first(),
        supportedFrequencies = supported,
    )
}
