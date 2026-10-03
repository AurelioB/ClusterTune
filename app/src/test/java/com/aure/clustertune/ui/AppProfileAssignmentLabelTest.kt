package com.aure.clustertune.ui

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileAssignmentLabelTest {

    @Test
    fun `auto tune slider rounds to whole fps and clamps to the current display`() {
        assertEquals(1, snapAutoTuneTargetFps(0.4f, maximumTargetFps = 60))
        assertEquals(59, snapAutoTuneTargetFps(58.6f, maximumTargetFps = 60))
        assertEquals(60, snapAutoTuneTargetFps(120f, maximumTargetFps = 60))
        assertEquals(77, snapAutoTuneTargetFps(76.6f, maximumTargetFps = 120))
    }

    @Test
    fun `auto tune maximum prefers the app display then the local display`() {
        assertEquals(120, autoTuneTargetMaximumFps(120, 60))
        assertEquals(60, autoTuneTargetMaximumFps(null, 60))
        assertEquals(60, autoTuneTargetMaximumFps(null, null))
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
