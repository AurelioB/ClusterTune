package com.aure.clustertune.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStateResolverTest {

    @Test
    fun `resolves stock as virtual profile`() {
        val policies = listOf(
            policy(id = 0, current = 3_532_800, stock = 3_532_800, supported = listOf(1_785_600, 3_532_800)),
            policy(id = 6, current = 4_320_000, stock = 4_320_000, supported = listOf(1_958_400, 4_320_000)),
        )

        val state = ProfileStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = policies.associate { it.id to it.currentMaxFreq },
                currentValues = policies.associate { it.id to it.stockMaxFreq },
                stockValues = policies.associate { it.id to it.stockMaxFreq },
            ),
        )

        assertEquals(ProfileStateResolver.STOCK_PROFILE_ID, state.activeDisplayProfileId)
        assertEquals("Stock", state.activeDisplayProfileName)
        assertEquals(ProfileStateResolver.STOCK_PROFILE_ID, state.selectedDisplayProfileId)
    }

    @Test
    fun `resolves manual when values do not match a profile`() {
        val policies = listOf(
            policy(id = 0, current = 2_500_000, stock = 3_532_800, supported = listOf(1_785_600, 2_500_000, 3_532_800)),
        )

        val state = ProfileStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = mapOf(0 to 2_500_000),
                currentValues = mapOf(0 to 2_500_000),
                stockValues = mapOf(0 to 3_532_800),
            ),
        )

        assertTrue(state.isManualActive)
        assertTrue(state.isManualSelection)
        assertEquals("Manual", state.activeDisplayProfileName)
    }

    @Test
    fun `resolves stock when actual values are hidden boost bins above selectable stock`() {
        val policies = listOf(
            policy(
                id = 3,
                current = 2_803_200,
                stock = 2_707_200,
                hardware = 2_803_200,
                supported = listOf(499_200, 1_920_000, 2_707_200),
            ),
            policy(
                id = 7,
                current = 3_187_200,
                stock = 2_956_800,
                hardware = 3_187_200,
                supported = listOf(595_200, 2_092_800, 2_956_800),
            ),
        )

        val state = ProfileStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = policies.associate { it.id to it.currentMaxFreq },
                currentValues = policies.associate { it.id to it.stockMaxFreq },
                stockValues = policies.associate { it.id to it.stockMaxFreq },
            ),
        )

        assertEquals(ProfileStateResolver.STOCK_PROFILE_ID, state.activeDisplayProfileId)
        assertEquals("Stock", state.activeDisplayProfileName)
    }

    @Test
    fun `resolves capped profile when actual value is boost above writable max`() {
        val policies = listOf(
            policy(
                id = 3,
                current = 2_803_200,
                stock = 2_707_200,
                hardware = 2_803_200,
                supported = listOf(499_200, 1_920_000, 2_707_200),
            ),
        )
        val profile = PerformanceProfile(
            id = "policy3_max",
            name = "Policy 3 Max",
            maxFrequencies = mapOf(3 to 2_707_200),
            source = ProfileSource.USER,
        )

        val state = ProfileStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = policies,
                actualValues = mapOf(3 to 2_803_200),
                currentValues = mapOf(3 to 2_707_200),
                userProfiles = listOf(profile),
                displayProfiles = listOf(profile),
            ),
        )

        assertEquals("policy3_max", state.activeDisplayProfileId)
        assertEquals("Policy 3 Max", state.activeDisplayProfileName)
    }

    private fun policy(
        id: Int,
        current: Int,
        stock: Int,
        supported: List<Int>,
        hardware: Int = stock,
    ) = CpuPolicyInfo(
        id = id,
        policyPath = "/sys/devices/system/cpu/cpufreq/policy$id",
        scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy$id/scaling_max_freq",
        currentMaxFreq = current,
        stockMaxFreq = stock,
        hardwareMaxFreq = hardware,
        minFreq = supported.first(),
        supportedFrequencies = supported,
    )
}
