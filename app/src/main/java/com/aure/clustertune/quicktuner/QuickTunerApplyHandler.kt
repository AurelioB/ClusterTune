package com.aure.clustertune.quicktuner

import android.widget.Toast
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TunerState

interface QuickTunerApplyRepository {
    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        gpuPolicy: GpuPolicyInfo?,
        selectedGpuMaxFrequencyHz: Int?,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        onHardwareApplied: (suspend (PerformanceRepository.ApplyOutcome) -> Unit)? = null,
    ): Result<PerformanceRepository.ApplyOutcome>

    suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String)
}

class PerformanceQuickTunerApplyRepository(
    private val repository: PerformanceRepository,
) : QuickTunerApplyRepository {
    override suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        gpuPolicy: GpuPolicyInfo?,
        selectedGpuMaxFrequencyHz: Int?,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        onHardwareApplied: (suspend (PerformanceRepository.ApplyOutcome) -> Unit)?,
    ): Result<PerformanceRepository.ApplyOutcome> {
        return repository.applyValues(
            policies = policies,
            selectedValues = selectedValues,
            gpuPolicy = gpuPolicy,
            selectedGpuMaxFrequencyHz = selectedGpuMaxFrequencyHz,
            isReset = isReset,
            appliedDisplayProfileId = appliedDisplayProfileId,
            onHardwareApplied = onHardwareApplied,
        )
    }

    override suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String) {
        repository.logProfileSwitch(profileId, profileName, trigger)
    }
}

class QuickTunerApplyHandler(
    private val repository: QuickTunerApplyRepository,
    private val showToast: (String, Int) -> Unit,
    private val refreshTile: () -> Unit,
    private val trigger: String = "Quick Settings dialog",
) {
    suspend fun applyCurrent(state: TunerState): Result<Unit> {
        val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
        val profileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID
        val profileName = appliedProfile?.name ?: "Manual"
        val displayName = appliedProfile?.name ?: "Custom values"

        val result = repository.applyValues(
            policies = state.policies,
            selectedValues = state.currentValues,
            gpuPolicy = state.gpuPolicy,
            selectedGpuMaxFrequencyHz = state.currentGpuMaxFrequencyHz,
            isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profileId,
            onHardwareApplied = {
                showToast(displayName, Toast.LENGTH_SHORT)
                refreshTile()
            },
        )

        return result.fold(
            onSuccess = {
                repository.logProfileSwitch(
                    profileId = profileId,
                    profileName = profileName,
                    trigger = trigger,
                )
                Result.success(Unit)
            },
            onFailure = { throwable ->
                showToast(throwable.message ?: "Failed to apply limits", Toast.LENGTH_LONG)
                Result.failure(throwable)
            },
        )
    }

    suspend fun applyProfile(state: TunerState, profile: PerformanceProfile): Result<Unit> {
        val result = repository.applyValues(
            policies = state.policies,
            selectedValues = profile.maxFrequencies,
            gpuPolicy = state.gpuPolicy,
            selectedGpuMaxFrequencyHz = profile.gpuMaxFrequencyHz,
            isReset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profile.id,
            onHardwareApplied = {
                showToast(profile.name, Toast.LENGTH_SHORT)
                refreshTile()
            },
        )

        return result.fold(
            onSuccess = {
                repository.logProfileSwitch(
                    profileId = profile.id,
                    profileName = profile.name,
                    trigger = "Quick Settings picker",
                )
                Result.success(Unit)
            },
            onFailure = { throwable ->
                showToast(throwable.message ?: "Failed to apply profile", Toast.LENGTH_LONG)
                Result.failure(throwable)
            },
        )
    }
}
