package com.aure.androidtuner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aure.androidtuner.data.PerformanceRepository
import com.aure.androidtuner.model.CpuPolicyInfo
import com.aure.androidtuner.model.PerformanceProfile
import com.aure.androidtuner.model.TunerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

class TunerViewModel(
    private val repository: PerformanceRepository,
) : ViewModel() {

    private val edits = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)

    val state: StateFlow<TunerState> = combine(
        repository.observeState(),
        edits,
        transientMessage,
        transientError,
    ) { repoState, localEdits, message, error ->
        repoState.copy(
            currentValues = repoState.currentValues + localEdits,
            statusMessage = message,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TunerState(),
    )

    fun setPolicyValue(policy: CpuPolicyInfo, rawValue: Int) {
        val snapped = snapToSupported(policy, rawValue)
        val updatedEdits = edits.value + (policy.id to snapped)
        edits.value = updatedEdits
        transientMessage.value = null
        transientError.value = null

        val baseValues = state.value.policies.associate { cpuPolicy ->
            cpuPolicy.id to (state.value.actualValues[cpuPolicy.id] ?: cpuPolicy.currentMaxFreq)
        }
        val pendingValues = baseValues + updatedEdits
        val selectedProfile = (state.value.bundledProfiles + state.value.userProfiles)
            .firstOrNull { it.id == state.value.selectedProfileId }

        if (selectedProfile != null && !matchesProfile(pendingValues, selectedProfile)) {
            viewModelScope.launch {
                repository.selectProfile(null)
            }
        }
    }

    fun applyProfile(profile: PerformanceProfile) {
        edits.value = edits.value + profile.maxFrequencies
        viewModelScope.launch {
            repository.selectProfile(profile.id)
        }
    }

    fun clearSelection() {
        viewModelScope.launch {
            repository.selectProfile(null)
        }
    }

    fun applyCurrent(state: TunerState) {
        transientMessage.value = null
        transientError.value = null

        viewModelScope.launch {
            val selectedProfile = (state.bundledProfiles + state.userProfiles)
                .firstOrNull { it.id == state.selectedProfileId }
            repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = selectedProfile?.isResetProfile == true,
            ).onSuccess { outcome ->
                edits.value = emptyMap()
                repository.refresh()
                transientMessage.value = if (outcome.verificationPassed) {
                    buildAppliedMessage(state, selectedProfile, outcome.commandOutput)
                } else {
                    buildVerificationFailureMessage(state, outcome.actualValues, outcome.commandOutput)
                }
            }.onFailure { throwable ->
                transientError.value = throwable.message ?: "Failed to apply limits"
            }
        }
    }

    fun saveCurrentAsPreset(name: String, state: TunerState) {
        if (name.isBlank()) {
            transientError.value = "Preset name is required"
            return
        }
        viewModelScope.launch {
            repository.saveUserPreset(name.trim(), state.currentValues)
            repository.selectProfile("user_${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}")
            transientMessage.value = "Saved preset \"$name\""
            transientError.value = null
        }
    }

    private fun snapToSupported(policy: CpuPolicyInfo, rawValue: Int): Int {
        return policy.supportedFrequencies.minByOrNull { supported -> abs(supported - rawValue) }
            ?: rawValue
    }

    private fun matchesProfile(
        values: Map<Int, Int>,
        profile: PerformanceProfile,
    ): Boolean {
        return profile.maxFrequencies.isNotEmpty() && profile.maxFrequencies.all { (policyId, value) ->
            values[policyId] == value
        }
    }

    private fun buildAppliedMessage(
        state: TunerState,
        selectedProfile: PerformanceProfile?,
        commandOutput: String?,
    ): String {
        val base = if (selectedProfile != null) {
            "Applied preset: ${selectedProfile.name}"
        } else {
            val summary = state.policies.joinToString(", ") { policy ->
                val value = state.currentValues[policy.id] ?: policy.currentMaxFreq
                "P${policy.id} ${formatFrequency(value)}"
            }
            "Applied manual settings: $summary"
        }
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    private fun buildVerificationFailureMessage(
        state: TunerState,
        actualValues: Map<Int, Int>,
        commandOutput: String?,
    ): String {
        val summary = state.policies.joinToString(", ") { policy ->
            val requested = state.currentValues[policy.id] ?: policy.currentMaxFreq
            val actual = actualValues[policy.id] ?: policy.currentMaxFreq
            "P${policy.id} requested ${formatFrequency(requested)}, actual ${formatFrequency(actual)}"
        }
        val base = "Apply did not stick: $summary"
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    companion object {
        fun factory(repository: PerformanceRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TunerViewModel(repository) as T
                }
            }
        }
    }
}
