package com.aure.clustertune.tile

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceTileStateTest {

    @Test
    fun `temporary active profile wins over persisted normal profile`() {
        val normal = profile("normal")
        val temporary = profile("temporary")

        val state = TunerState(
            displayProfiles = listOf(normal, temporary),
            activeDisplayProfileId = temporary.id,
            lastAppliedDisplayProfileId = normal.id,
        )

        assertEquals(temporary.id, resolveTileProfileId(state))
    }

    @Test
    fun `persisted profile is used when active profile is unavailable`() {
        val normal = profile("normal")
        val state = TunerState(
            displayProfiles = listOf(normal),
            activeDisplayProfileId = "missing",
            lastAppliedDisplayProfileId = normal.id,
        )

        assertEquals(normal.id, resolveTileProfileId(state))
    }

    private fun profile(id: String) = PerformanceProfile(
        id = id,
        name = id,
        maxFrequencies = mapOf(0 to 1_000_000),
        source = ProfileSource.USER,
    )
}
