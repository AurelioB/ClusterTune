package com.aure.clustertune.ui

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class CompactProfilePickerStateTest {

    @Test
    fun `picker includes stock and excludes unrelated virtual profiles`() {
        val bundled = profile(id = "small", name = "Small", source = ProfileSource.BUNDLED)
        val stock = profile(
            id = ProfileStateResolver.STOCK_PROFILE_ID,
            name = "Stock",
            source = ProfileSource.VIRTUAL,
        )
        val manual = profile(
            id = ProfileStateResolver.MANUAL_PROFILE_ID,
            name = "Manual",
            source = ProfileSource.VIRTUAL,
        )

        val result = profilesForCompactPicker(listOf(bundled, stock, manual))

        assertEquals(listOf(bundled, stock), result)
    }

    private fun profile(
        id: String,
        name: String,
        source: ProfileSource,
    ): PerformanceProfile {
        return PerformanceProfile(
            id = id,
            name = name,
            maxFrequencies = mapOf(0 to 1_000_000),
            source = source,
        )
    }
}
