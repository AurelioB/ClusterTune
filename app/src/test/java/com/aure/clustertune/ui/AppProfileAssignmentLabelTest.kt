package com.aure.clustertune.ui

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileAssignmentLabelTest {

    @Test
    fun `auto tune slider rounds to whole fps and clamps to supported range`() {
        assertEquals(15, snapAutoTuneTargetFps(14.4f))
        assertEquals(77, snapAutoTuneTargetFps(76.6f))
        assertEquals(240, snapAutoTuneTargetFps(240.6f))
    }

    @Test
    fun `labels auto custom named and missing assignments`() {
        val named = PerformanceProfile("balanced", "Balanced", mapOf(0 to 1_000), ProfileSource.USER)
        val profiles = mapOf(named.id to named)

        assertEquals(
            "Auto 77 FPS",
            appProfileAssignmentLabel(assignment(autoTuneTargetFps = 77), profiles),
        )
        assertEquals(
            "Custom",
            appProfileAssignmentLabel(assignment(customMaxFrequencies = mapOf(0 to 800)), profiles),
        )
        assertEquals(
            "Balanced",
            appProfileAssignmentLabel(assignment(profileId = named.id), profiles),
        )
        assertEquals(
            "Missing profile",
            appProfileAssignmentLabel(assignment(profileId = "missing"), profiles),
        )
    }

    private fun assignment(
        profileId: String? = null,
        customMaxFrequencies: Map<Int, Int> = emptyMap(),
        autoTuneTargetFps: Int? = null,
    ) = AppProfileAssignment(
        packageName = "example.app",
        appLabel = "Example",
        profileId = profileId,
        customMaxFrequencies = customMaxFrequencies,
        autoTuneTargetFps = autoTuneTargetFps,
    )
}
