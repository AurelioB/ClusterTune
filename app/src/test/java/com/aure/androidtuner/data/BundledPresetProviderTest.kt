package com.aure.androidtuner.data

import com.aure.androidtuner.model.CpuPolicyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledPresetProviderTest {

    private val provider = BundledPresetProvider()

    @Test
    fun `returns elite presets when policy0 and policy6 are present`() {
        val profiles = provider.createProfiles(
            listOf(
                policy(id = 0, stockMax = 3_532_800, supported = listOf(1_785_600, 2_227_200, 2_745_600, 3_532_800)),
                policy(id = 6, stockMax = 4_320_000, supported = listOf(1_958_400, 2_246_400, 3_072_000, 4_320_000)),
            ),
        )

        assertEquals(listOf("Small Underclock", "Medium Underclock", "Large Underclock", "Reset / Stock"), profiles.map { it.name })
        assertTrue(profiles.last().isResetProfile)
    }

    @Test
    fun `returns empty when required policies are missing`() {
        val profiles = provider.createProfiles(listOf(policy(id = 2, stockMax = 2_500_000, supported = listOf(800_000, 2_500_000))))
        assertTrue(profiles.isEmpty())
    }

    private fun policy(id: Int, stockMax: Int, supported: List<Int>) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy$id",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        currentMaxFreq = stockMax,
        stockMaxFreq = stockMax,
        minFreq = supported.first(),
        supportedFrequencies = supported,
    )
}
