package com.aure.clustertune.data

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRepositoryAppProfileTest {

    @Test
    fun `stock remains a supported app profile assignment`() {
        val bundled = profile(id = "small", source = ProfileSource.BUNDLED)
        val assignments = listOf(
            assignment(profileId = bundled.id),
            assignment(profileId = ProfileStateResolver.STOCK_PROFILE_ID, packageName = "stock.app"),
            assignment(profileId = ProfileStateResolver.MANUAL_PROFILE_ID, packageName = "manual.app"),
            assignment(profileId = "missing", packageName = "missing.app"),
            AppProfileAssignment("custom.app", "Custom", customMaxFrequencies = mapOf(0 to 900_000)),
            AppProfileAssignment("invalid-custom.app", "Invalid", customMaxFrequencies = mapOf(9 to 900_000)),
        )

        val result = supportedAppProfileAssignments(
            assignments = assignments,
            realProfiles = listOf(bundled),
        )

        assertEquals(
            listOf(assignments[0], assignments[1], assignments[4]),
            result,
        )
    }

    private fun assignment(
        profileId: String,
        packageName: String = "game.app",
    ): AppProfileAssignment {
        return AppProfileAssignment(
            packageName = packageName,
            appLabel = packageName,
            profileId = profileId,
        )
    }

    private fun profile(
        id: String,
        source: ProfileSource,
    ): PerformanceProfile {
        return PerformanceProfile(
            id = id,
            name = id,
            maxFrequencies = mapOf(0 to 1_000_000),
            source = source,
        )
    }
}
