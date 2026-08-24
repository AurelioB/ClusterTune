package com.aure.clustertune.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProfileAssignmentTest {

    @Test
    fun `named custom and every positive auto tune target are valid individually`() {
        assertTrue(assignment(profileId = "balanced").hasValidTarget)
        assertTrue(assignment(customMaxFrequencies = mapOf(0 to 1_000_000)).hasValidTarget)
        assertTrue(assignment(customGpuMaxFrequencyHz = 500_000_000).hasValidTarget)
        AUTO_TUNE_TARGET_FPS_PRESETS.forEach { targetFps ->
            assertTrue(assignment(autoTuneTargetFps = targetFps).hasValidTarget)
        }
        assertTrue(assignment(autoTuneTargetFps = MIN_AUTO_TUNE_TARGET_FPS).hasValidTarget)
        assertTrue(assignment(autoTuneTargetFps = 77).hasValidTarget)
        assertTrue(assignment(autoTuneTargetFps = Int.MAX_VALUE).hasValidTarget)
    }

    @Test
    fun `missing mixed and non-positive auto tune targets are invalid`() {
        assertFalse(assignment().hasValidTarget)
        assertFalse(assignment(autoTuneTargetFps = 0).hasValidTarget)
        assertFalse(assignment(autoTuneTargetFps = -1).hasValidTarget)
        assertFalse(assignment(profileId = "balanced", autoTuneTargetFps = 60).hasValidTarget)
        assertFalse(
            assignment(
                customMaxFrequencies = mapOf(0 to 1_000_000),
                autoTuneTargetFps = 60,
            ).hasValidTarget,
        )
    }

    private fun assignment(
        profileId: String? = null,
        customMaxFrequencies: Map<Int, Int> = emptyMap(),
        customGpuMaxFrequencyHz: Int? = null,
        autoTuneTargetFps: Int? = null,
    ) = AppProfileAssignment(
        packageName = "example.app",
        appLabel = "Example",
        profileId = profileId,
        customMaxFrequencies = customMaxFrequencies,
        customGpuMaxFrequencyHz = customGpuMaxFrequencyHz,
        autoTuneTargetFps = autoTuneTargetFps,
    )
}
