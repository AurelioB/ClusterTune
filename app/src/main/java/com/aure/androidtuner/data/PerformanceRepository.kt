package com.aure.androidtuner.data

import com.aure.androidtuner.model.CpuPolicyInfo
import com.aure.androidtuner.model.PerformanceProfile
import com.aure.androidtuner.model.ProfileSource
import com.aure.androidtuner.model.TunerState
import com.aure.androidtuner.root.PerformanceCommandBuilder
import com.aure.androidtuner.root.RootCommandRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

class PerformanceRepository(
    private val detector: CpuPolicyDetector,
    private val bundledPresetProvider: BundledPresetProvider,
    private val profileStorage: ProfileStorage,
    private val commandBuilder: PerformanceCommandBuilder,
    private val rootCommandRunner: RootCommandRunner,
) {
    data class ApplyOutcome(
        val actualValues: Map<Int, Int>,
        val verificationPassed: Boolean,
        val commandOutput: String?,
    )

    private val refreshToken = MutableStateFlow(0)

    fun observeState(): Flow<TunerState> {
        return combine(
            refreshToken,
            profileStorage.userProfiles,
            profileStorage.lastValues,
            profileStorage.selectedProfileId,
        ) { _, userProfiles, lastValues, selectedProfileId ->
            val policies = detector.detectPolicies()
            val bundledProfiles = bundledPresetProvider.createProfiles(policies)
            val actualValues = policies.associate { it.id to it.currentMaxFreq }
            val defaultValues = policies.associate { it.id to it.currentMaxFreq }
            TunerState(
                isLoading = false,
                isPServerAvailable = rootCommandRunner.isAvailable,
                policies = policies,
                actualValues = actualValues,
                currentValues = mergeValues(policies, defaultValues, lastValues),
                bundledProfiles = bundledProfiles,
                userProfiles = userProfiles,
                selectedProfileId = selectedProfileId,
            )
        }
    }

    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
    ): Result<ApplyOutcome> {
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        val script = commandBuilder.buildApplyScript(policies, filtered, isReset)
        return rootCommandRunner.executeScript(script).mapCatching { output ->
            profileStorage.persistLastValues(filtered)
            val refreshedPolicies = detector.detectPolicies()
            val actualValues = refreshedPolicies.associate { it.id to it.currentMaxFreq }
            refresh()
            ApplyOutcome(
                actualValues = actualValues,
                verificationPassed = filtered.all { (policyId, requestedValue) ->
                    actualValues[policyId] == requestedValue
                },
                commandOutput = output,
            )
        }
    }

    suspend fun saveUserPreset(name: String, values: Map<Int, Int>) {
        val sanitizedId = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        profileStorage.saveUserProfile(
            PerformanceProfile(
                id = "user_$sanitizedId",
                name = name,
                maxFrequencies = values,
                source = ProfileSource.USER,
            ),
        )
    }

    suspend fun selectProfile(profileId: String?) {
        profileStorage.persistSelectedProfile(profileId)
    }

    fun refresh() {
        refreshToken.update { it + 1 }
    }

    private fun mergeValues(
        policies: List<CpuPolicyInfo>,
        currentValues: Map<Int, Int>,
        persistedValues: Map<Int, Int>,
    ): Map<Int, Int> {
        return policies.associate { policy ->
            val supported = policy.supportedFrequencies.toSet()
            val persisted = persistedValues[policy.id]
            val safeValue = if (persisted != null && (persisted in supported || persisted == policy.stockMaxFreq)) {
                persisted
            } else {
                currentValues[policy.id] ?: policy.currentMaxFreq
            }
            policy.id to safeValue
        }
    }
}
