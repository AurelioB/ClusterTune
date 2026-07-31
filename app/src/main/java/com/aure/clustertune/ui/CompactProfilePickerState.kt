package com.aure.clustertune.ui

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.ProfileStateResolver

internal fun profilesForCompactPicker(
    profiles: List<PerformanceProfile>,
): List<PerformanceProfile> {
    return profiles.filter { profile ->
        profile.source != ProfileSource.VIRTUAL ||
            profile.id == ProfileStateResolver.STOCK_PROFILE_ID
    }
}
