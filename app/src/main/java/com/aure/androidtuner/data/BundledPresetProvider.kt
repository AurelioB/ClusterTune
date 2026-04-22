package com.aure.androidtuner.data

import com.aure.androidtuner.model.CpuPolicyInfo
import com.aure.androidtuner.model.PerformanceProfile
import com.aure.androidtuner.model.ProfileSource

class BundledPresetProvider {

    fun createProfiles(policies: List<CpuPolicyInfo>): List<PerformanceProfile> {
        val policyIds = policies.associateBy { it.id }
        val cluster0 = policyIds[0] ?: return emptyList()
        val cluster6 = policyIds[6] ?: return emptyList()

        return listOf(
            PerformanceProfile(
                id = "bundled_small",
                name = "Small Underclock",
                maxFrequencies = mapOf(0 to 2_745_600, 6 to 3_072_000),
                source = ProfileSource.BUNDLED,
            ),
            PerformanceProfile(
                id = "bundled_medium",
                name = "Medium Underclock",
                maxFrequencies = mapOf(0 to 2_227_200, 6 to 2_246_400),
                source = ProfileSource.BUNDLED,
            ),
            PerformanceProfile(
                id = "bundled_large",
                name = "Large Underclock",
                maxFrequencies = mapOf(0 to 1_785_600, 6 to 1_958_400),
                source = ProfileSource.BUNDLED,
            ),
            PerformanceProfile(
                id = "bundled_reset",
                name = "Stock",
                maxFrequencies = mapOf(0 to cluster0.stockMaxFreq, 6 to cluster6.stockMaxFreq),
                source = ProfileSource.BUNDLED,
                isResetProfile = true,
            ),
        ).filter { profile ->
            profile.maxFrequencies.all { (policyId, frequency) ->
                val policy = policyIds[policyId] ?: return@all false
                frequency in policy.supportedFrequencies || frequency == policy.stockMaxFreq
            }
        }
    }
}
