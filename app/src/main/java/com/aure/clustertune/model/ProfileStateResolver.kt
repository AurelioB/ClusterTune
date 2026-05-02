package com.aure.clustertune.model

object ProfileStateResolver {

    const val MANUAL_PROFILE_ID = "virtual_manual"
    const val STOCK_PROFILE_ID = "virtual_stock"

    fun resolve(state: TunerState, currentValues: Map<Int, Int> = state.currentValues): TunerState {
        val stockProfile = buildStockProfile(state.policies, state.stockValues)
        val realProfiles = (state.bundledProfiles + state.userProfiles).sortedBy { it.order }
        val userProfiles = realProfiles.filter { it.source == ProfileSource.USER }
        val bundledProfiles = realProfiles.filter { it.source == ProfileSource.BUNDLED }
        val displayProfiles = state.displayProfiles.ifEmpty {
            buildDisplayProfiles(
                realProfiles = realProfiles,
                stockProfile = stockProfile,
                orderedIds = emptyList(),
            )
        }
        val selectedProfile = resolveProfileForValues(
            values = currentValues,
            profiles = displayProfiles,
            preferredId = state.selectedProfileId,
        )
        val activeProfile = resolveProfileForValues(
            values = state.actualValues,
            profiles = displayProfiles,
            preferredId = state.selectedProfileId,
        )
        val hasPolicies = state.policies.isNotEmpty()

        return state.copy(
            currentValues = currentValues,
            stockProfile = stockProfile,
            bundledProfiles = bundledProfiles,
            userProfiles = userProfiles,
            displayProfiles = displayProfiles,
            selectedDisplayProfileId = selectedProfile?.id,
            selectedDisplayProfileName = selectedProfile?.name,
            activeDisplayProfileId = when {
                activeProfile != null -> activeProfile.id
                hasPolicies -> MANUAL_PROFILE_ID
                else -> null
            },
            activeDisplayProfileName = when {
                activeProfile != null -> activeProfile.name
                hasPolicies -> "Manual"
                else -> null
            },
            isManualSelection = hasPolicies && selectedProfile == null,
            isManualActive = hasPolicies && activeProfile == null,
        )
    }

    fun buildStockProfile(
        policies: List<CpuPolicyInfo>,
        stockValues: Map<Int, Int>,
    ): PerformanceProfile? {
        if (policies.isEmpty()) return null
        return PerformanceProfile(
            id = STOCK_PROFILE_ID,
            name = "Stock",
            maxFrequencies = policies.associate { policy ->
                policy.id to (stockValues[policy.id] ?: policy.stockMaxFreq)
            },
            source = ProfileSource.VIRTUAL,
            isResetProfile = true,
            isEditable = false,
            isDeletable = false,
        )
    }

    fun matchesProfile(values: Map<Int, Int>, profile: PerformanceProfile): Boolean {
        return profile.maxFrequencies.isNotEmpty() && profile.maxFrequencies.all { (policyId, value) ->
            values[policyId] == value
        }
    }

    fun buildDisplayProfiles(
        realProfiles: List<PerformanceProfile>,
        stockProfile: PerformanceProfile?,
        orderedIds: List<String>,
    ): List<PerformanceProfile> {
        val allProfiles = realProfiles + listOfNotNull(stockProfile)
        if (orderedIds.isEmpty()) return allProfiles
        val byId = allProfiles.associateBy { it.id }
        val ordered = orderedIds.mapNotNull(byId::get)
        val missing = allProfiles.filter { it.id !in orderedIds }
        return ordered + missing
    }

    private fun resolveProfileForValues(
        values: Map<Int, Int>,
        profiles: List<PerformanceProfile>,
        preferredId: String?,
    ): PerformanceProfile? {
        if (values.isEmpty()) return null
        val preferred = preferredId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (preferred != null && matchesProfile(values, preferred)) {
            return preferred
        }
        return profiles.firstOrNull { matchesProfile(values, it) }
    }
}
