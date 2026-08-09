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
                currentValues = policies.associate { it.id to it.selectableMaxFreq },
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
                currentValues = policies.associate { it.id to it.selectableMaxFreq },
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

    @Test
    fun `legacy cpu-only profile leaves gpu unspecified while gpu profile requires matching cap`() {
        val cpu = policy(0, 2_000_000, 2_500_000, listOf(1_000_000, 2_000_000, 2_500_000))
        val gpu = GpuPolicyInfo("/gpu", "/gpu/max", currentMaxFrequencyHz = 600, selectableMaxFrequencyHz = 600, observedMaxFrequencyHz = 800, supportedFrequenciesHz = listOf(400, 600, 800))
        val cpuOnly = PerformanceProfile("cpu", "CPU", mapOf(0 to 2_000_000), ProfileSource.USER)
        val gpuAware = cpuOnly.copy(id = "gpu", name = "GPU", gpuMaxFrequencyHz = 600)
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), cpuOnly, listOf(cpu), gpu, 400))
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), cpuOnly, listOf(cpu), gpu, 600))
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), cpuOnly, listOf(cpu), gpu, 800))
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), gpuAware, listOf(cpu), gpu, 600))
        assertTrue(!ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), gpuAware, listOf(cpu), gpu, 400))
    }

    @Test
    fun `stock profile includes observed gpu ceiling`() {
        val cpu = policy(0, 2_500_000, 2_500_000, listOf(1_000_000, 2_500_000))
        val gpu = GpuPolicyInfo("/gpu", "/gpu/max", currentMaxFrequencyHz = 600, selectableMaxFrequencyHz = 600, observedMaxFrequencyHz = 800, supportedFrequenciesHz = listOf(400, 600, 800))
        assertEquals(800, ProfileStateResolver.buildStockProfile(listOf(cpu), gpu)?.gpuMaxFrequencyHz)
    }

    @Test
    fun `legacy null gpu profile leaves gpu domain unspecified`() {
        val cpu = policy(0, 2_000_000, 2_000_000, listOf(1_000_000, 2_000_000))
        val gpu = GpuPolicyInfo(
            "/gpu", "/gpu/max", currentMaxFrequencyHz = 600,
            selectableMaxFrequencyHz = 600, observedMaxFrequencyHz = 800,
            supportedFrequenciesHz = listOf(400, 600, 800),
        )
        val legacy = PerformanceProfile("legacy", "Legacy", mapOf(0 to 2_000_000), ProfileSource.USER)

        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), legacy, listOf(cpu), gpu, 800))
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), legacy, listOf(cpu), gpu, 600))
        assertTrue(ProfileStateResolver.matchesProfile(mapOf(0 to 2_000_000), legacy, listOf(cpu), gpu, 400))
    }

    @Test
    fun `switching from capped gpu profile to legacy profile leaves gpu unchanged`() {
        val cpu = policy(0, 2_000_000, 2_000_000, listOf(1_000_000, 2_000_000))
        val gpu = GpuPolicyInfo(
            "/gpu", "/gpu/max", currentMaxFrequencyHz = 600,
            selectableMaxFrequencyHz = 600, observedMaxFrequencyHz = 800,
            supportedFrequenciesHz = listOf(400, 600, 800),
        )
        val capped = PerformanceProfile("capped", "Capped", mapOf(0 to 2_000_000), ProfileSource.USER, gpuMaxFrequencyHz = 600)
        val legacy = PerformanceProfile("legacy", "Legacy", mapOf(0 to 2_000_000), ProfileSource.USER)
        val state = ProfileStateResolver.resolve(
            TunerState(
                isLoading = false,
                policies = listOf(cpu),
                gpuPolicy = gpu,
                actualValues = mapOf(0 to 2_000_000),
                currentValues = mapOf(0 to 2_000_000),
                actualGpuMaxFrequencyHz = 800,
                currentGpuMaxFrequencyHz = 800,
                userProfiles = listOf(capped, legacy),
                selectedProfileId = legacy.id,
            ),
        )

        assertEquals(legacy.id, state.activeDisplayProfileId)
        assertEquals(legacy.id, state.selectedDisplayProfileId)
        assertEquals(800, state.currentGpuMaxFrequencyHz)
    }

    @Test
    fun `sleep default chooses large underclock regardless of display order`() {
        val profiles = listOf(
            PerformanceProfile("small", "Small Underclock", mapOf(0 to 2_000), ProfileSource.BUNDLED, order = 0),
            PerformanceProfile("stock", "Stock", mapOf(0 to 3_000), ProfileSource.VIRTUAL, order = 1),
            PerformanceProfile("large", "Large Underclock", mapOf(0 to 1_000), ProfileSource.BUNDLED, order = 2),
        )

        assertEquals("large", ProfileStateResolver.defaultSleepProfileId(profiles))
    }

    @Test
    fun `sleep default falls back to most restrictive bundled profile`() {
        val profiles = listOf(
            PerformanceProfile("m", "Balanced", mapOf(0 to 2_000, 1 to 2_500), ProfileSource.BUNDLED),
            PerformanceProfile("s", "Quiet", mapOf(0 to 1_000, 1 to 1_500), ProfileSource.BUNDLED),
        )

        assertEquals("s", ProfileStateResolver.defaultSleepProfileId(profiles))
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
        selectableMaxFreq = stock,
        observedMaxFreq = hardware,
        minFreq = supported.first(),
        supportedFrequencies = supported,
    )
}
