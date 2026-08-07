package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProfileMonitorServiceTest {

    @Test
    fun transitionRetryKeyIncludesCustomGpuTarget() {
        val cpu = AppProfileAssignment(
            packageName = "com.example.game",
            appLabel = "Game",
            customMaxFrequencies = mapOf(0 to 1_000_000),
        )
        val gpu = cpu.copy(customGpuMaxFrequencyHz = 400_000_000)

        val cpuKey = appProfileTransitionRetryKey(cpu, null)
        val gpuKey = appProfileTransitionRetryKey(gpu, null)

        assertNotEquals(cpuKey, gpuKey)
        assertTrue(gpuKey.contains("400000000"))
    }

    @Test
    fun transitionRetryKeyIncludesNamedProfileGpuTarget() {
        val assignment = AppProfileAssignment(
            packageName = "com.example.game",
            appLabel = "Game",
            profileId = "quiet",
        )
        val cpuOnly = PerformanceProfile(
            id = "quiet",
            name = "Quiet",
            maxFrequencies = mapOf(0 to 1_000_000),
            source = ProfileSource.USER,
        )
        val gpuLimited = cpuOnly.copy(gpuMaxFrequencyHz = 400_000_000)

        assertNotEquals(
            appProfileTransitionRetryKey(assignment, cpuOnly),
            appProfileTransitionRetryKey(assignment, gpuLimited),
        )
    }
}
