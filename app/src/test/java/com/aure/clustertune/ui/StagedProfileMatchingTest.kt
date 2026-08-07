package com.aure.clustertune.ui

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedProfileMatchingTest {

    private val cpu = CpuPolicyInfo(
        id = 0,
        policyPath = "/cpu/policy0",
        scalingMaxPath = "/cpu/policy0/scaling_max_freq",
        currentMaxFreq = 2_000_000,
        selectableMaxFreq = 2_000_000,
        observedMaxFreq = 2_000_000,
        minFreq = 1_000_000,
        supportedFrequencies = listOf(1_000_000, 2_000_000),
    )
    private val gpu = GpuPolicyInfo(
        policyPath = "/gpu",
        maxFrequencyPath = "/gpu/max",
        currentMaxFrequencyHz = 600,
        selectableMaxFrequencyHz = 600,
        observedMaxFrequencyHz = 800,
        supportedFrequenciesHz = listOf(400, 600, 800),
    )
    private val state = TunerState(policies = listOf(cpu), gpuPolicy = gpu)
    private val cpuOnly = PerformanceProfile(
        id = "cpu",
        name = "CPU only",
        maxFrequencies = mapOf(0 to 2_000_000),
        source = ProfileSource.USER,
    )
    private val gpuAware = cpuOnly.copy(id = "gpu", gpuMaxFrequencyHz = 600)

    @Test
    fun legacyProfileLeavesGpuUnspecified() {
        assertTrue(profileMatchesStagedValues(mapOf(0 to 2_000_000), cpuOnly, state, 800))
        assertTrue(profileMatchesStagedValues(mapOf(0 to 2_000_000), cpuOnly, state, 600))
        assertTrue(profileMatchesStagedValues(mapOf(0 to 2_000_000), cpuOnly, state, null))
    }

    @Test
    fun explicitGpuProfileStillRequiresItsGpuValue() {
        assertTrue(profileMatchesStagedValues(mapOf(0 to 2_000_000), gpuAware, state, 600))
        assertFalse(profileMatchesStagedValues(mapOf(0 to 2_000_000), gpuAware, state, 400))
    }
}
