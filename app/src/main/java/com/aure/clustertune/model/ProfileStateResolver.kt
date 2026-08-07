package com.aure.clustertune.model

object ProfileStateResolver {

    const val MANUAL_PROFILE_ID = "virtual_manual"
    const val STOCK_PROFILE_ID = "virtual_stock"

    fun resolve(state: TunerState, currentValues: Map<Int, Int> = state.currentValues): TunerState {
        val stockProfile = buildStockProfile(state.policies, state.gpuPolicy)
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
            policies = state.policies,
            gpuPolicy = state.gpuPolicy,
            gpuValue = state.currentGpuMaxFrequencyHz,
        )
        val activeProfile = resolveProfileForValues(
            values = state.actualValues,
            profiles = displayProfiles,
            preferredId = state.selectedProfileId,
            policies = state.policies,
            gpuPolicy = state.gpuPolicy,
            gpuValue = state.actualGpuMaxFrequencyHz,
        )
        // CPU policies are the required execution domain. GPU tuning is an
        // optional additional domain and must not make an otherwise unusable
        // device appear tunable.
        val hasTuningPolicies = state.policies.isNotEmpty()

        return state.copy(
            currentValues = currentValues,
            bundledProfiles = bundledProfiles,
            userProfiles = userProfiles,
            displayProfiles = displayProfiles,
            selectedDisplayProfileId = selectedProfile?.id,
            selectedDisplayProfileName = selectedProfile?.name,
            activeDisplayProfileId = when {
                activeProfile != null -> activeProfile.id
                hasTuningPolicies -> MANUAL_PROFILE_ID
                else -> null
            },
            activeDisplayProfileName = when {
                activeProfile != null -> activeProfile.name
                hasTuningPolicies -> "Manual"
                else -> null
            },
            isManualSelection = hasTuningPolicies && selectedProfile == null,
            isManualActive = hasTuningPolicies && activeProfile == null,
        )
    }

    fun buildStockProfile(policies: List<CpuPolicyInfo>, gpuPolicy: GpuPolicyInfo? = null): PerformanceProfile? {
        if (policies.isEmpty()) return null
        return PerformanceProfile(
            id = STOCK_PROFILE_ID,
            name = "Stock",
            maxFrequencies = policies.associate { policy ->
                policy.id to policy.observedMaxFreq
            },
            gpuMaxFrequencyHz = gpuPolicy?.observedMaxFrequencyHz,
            source = ProfileSource.VIRTUAL,
            isEditable = false,
            isDeletable = false,
        )
    }

    fun matchesProfile(
        values: Map<Int, Int>,
        profile: PerformanceProfile,
        policies: List<CpuPolicyInfo> = emptyList(),
        gpuPolicy: GpuPolicyInfo? = null,
        gpuValue: Int? = null,
    ): Boolean {
        val policiesById = policies.associateBy { it.id }
        val cpuMatches = profile.maxFrequencies.isNotEmpty() && profile.maxFrequencies.all { (policyId, value) ->
            val actual = values[policyId] ?: return@all false
            val policy = policiesById[policyId] ?: return@all false
            isPolicyValueSatisfied(policy = policy, requestedValue = value, actualValue = actual)
        }
        // Legacy profiles predate the optional GPU field. Keep that field
        // unspecified: selecting one must not change the GPU domain. Bundled
        // profiles that mean Stock are materialized with an explicit value by
        // the repository before they reach the resolver.
        val gpuMatches = when {
            gpuPolicy == null -> true
            profile.gpuMaxFrequencyHz == null -> true
            gpuValue == null -> false
            else -> isGpuValueSatisfied(gpuPolicy, profile.gpuMaxFrequencyHz, gpuValue)
        }
        return cpuMatches && gpuMatches
    }

    fun isGpuValueSatisfied(policy: GpuPolicyInfo, requestedValue: Int, actualValue: Int): Boolean {
        if (actualValue == requestedValue) return true
        return requestedValue >= policy.selectableMaxFrequencyHz &&
            requestedValue <= policy.observedMaxFrequencyHz &&
            actualValue >= policy.selectableMaxFrequencyHz &&
            actualValue <= policy.observedMaxFrequencyHz
    }

    fun isPolicyValueSatisfied(
        policy: CpuPolicyInfo,
        requestedValue: Int,
        actualValue: Int,
    ): Boolean {
        if (actualValue == requestedValue) return true
        val selectableMax = policy.selectableMaxFreq
        return requestedValue >= selectableMax &&
            requestedValue <= policy.observedMaxFreq &&
            actualValue >= selectableMax &&
            actualValue <= policy.observedMaxFreq
    }

    fun preferredProfileForCurrentValues(state: TunerState): PerformanceProfile? {
        return state.displayProfiles.firstOrNull { profile ->
            profile.id == state.selectedDisplayProfileId && matchesProfile(state.currentValues, profile, state.policies, state.gpuPolicy, state.currentGpuMaxFrequencyHz)
        } ?: state.displayProfiles.firstOrNull { profile ->
            matchesProfile(state.currentValues, profile, state.policies, state.gpuPolicy, state.currentGpuMaxFrequencyHz)
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
        policies: List<CpuPolicyInfo>,
        gpuPolicy: GpuPolicyInfo? = null,
        gpuValue: Int? = null,
    ): PerformanceProfile? {
        if (values.isEmpty()) return null
        val preferred = preferredId?.let { id -> profiles.firstOrNull { it.id == id } }
        if (preferred != null && matchesProfile(values, preferred, policies, gpuPolicy, gpuValue)) {
            return preferred
        }
        return profiles.firstOrNull { matchesProfile(values, it, policies, gpuPolicy, gpuValue) }
    }
}
