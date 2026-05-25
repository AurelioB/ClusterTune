package com.aure.clustertune.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.root.OdinScriptHandoff
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

class TunerViewModel(
    private val repository: PerformanceRepository,
    private val settingsStorage: SettingsStorage,
    private val odinScriptHandoff: OdinScriptHandoff,
) : ViewModel() {

    /**
     * State surfaced to the UI when the standard PServer apply path
     * failed verification and the device has Odin Settings available
     * as a fallback. The UI shows a handoff dialog and, if the user
     * accepts, the user is taken to Odin Settings to run the script
     * through "Run script as Root".
     */
    data class HandoffRequest(
        val scriptPath: String,
        val userVisiblePath: String,
        val showTutorial: Boolean,
        val profileName: String,
    )

    private val handoffRequest = MutableStateFlow<HandoffRequest?>(null)
    val handoffRequestFlow: StateFlow<HandoffRequest?> = handoffRequest

    private val edits = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)

    val state: StateFlow<TunerState> = combine(
        repository.observeState(),
        edits,
        transientMessage,
        transientError,
    ) { repoState, localEdits, message, error ->
        ProfileStateResolver.resolve(
            repoState.copy(
                statusMessage = message,
                errorMessage = error,
            ),
            currentValues = repoState.currentValues + localEdits,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TunerState(),
    )

    val settings: StateFlow<AppSettings> = settingsStorage.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings(),
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
        val selectedProfile = state.value.displayProfiles
            .firstOrNull { it.id == state.value.selectedProfileId }

        if (selectedProfile != null &&
            !ProfileStateResolver.matchesProfile(pendingValues, selectedProfile, state.value.policies)
        ) {
            viewModelScope.launch {
                repository.selectProfile(null)
            }
        }
    }

    fun applyProfile(profile: PerformanceProfile) {
        edits.value = edits.value + profile.maxFrequencies
        viewModelScope.launch {
            repository.selectProfile(profile.id.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
        }
    }

    fun clearSelection() {
        viewModelScope.launch {
            repository.selectProfile(null)
        }
    }

    fun consumeStatusMessage() {
        transientMessage.value = null
    }

    fun consumeErrorMessage() {
        transientError.value = null
    }

    fun applyCurrent(state: TunerState, onApplied: (String) -> Unit = {}) {
        transientMessage.value = null
        transientError.value = null

        viewModelScope.launch {
            val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
            val profileName = appliedProfile?.name ?: "Manual"
            val applyResult = repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
            )
            applyResult.onSuccess { outcome ->
                edits.value = emptyMap()
                if (outcome.verificationPassed) {
                    Log.d(LOG_TAG, "applyCurrent: verification passed")
                    transientMessage.value = buildAppliedMessage(appliedProfile, outcome.commandOutput)
                    transientError.value = null
                } else {
                    Log.d(
                        LOG_TAG,
                        "applyCurrent: verification failed, handoff available=${odinScriptHandoff.isAvailable}",
                    )
                    if (odinScriptHandoff.isAvailable) {
                        val scriptPath = odinScriptHandoff.writeScript(outcome.appliedScript)
                        Log.d(LOG_TAG, "applyCurrent: scriptPath=$scriptPath")
                        if (scriptPath != null) {
                            val hasSeenTutorial = settings.value.hasSeenOdinHandoffTutorial
                            handoffRequest.value = HandoffRequest(
                                scriptPath = scriptPath,
                                userVisiblePath = odinScriptHandoff.userVisibleScriptPath(),
                                showTutorial = !hasSeenTutorial,
                                profileName = profileName,
                            )
                            transientMessage.value = null
                            transientError.value = null
                        } else {
                            transientError.value =
                                "Could not write apply script to Downloads. Check storage permissions."
                        }
                    } else {
                        transientMessage.value =
                            buildVerificationFailureMessage(state, outcome.actualValues, outcome.commandOutput)
                        transientError.value = null
                    }
                }
            }.onFailure { throwable ->
                Log.w(LOG_TAG, "applyCurrent: applyValues failed", throwable)
                transientError.value = throwable.message ?: "Failed to apply limits"
            }
            if (applyResult.isSuccess && applyResult.getOrNull()?.verificationPassed == true) {
                repository.selectProfile(appliedProfile?.id?.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
                onApplied(profileName)
            }
        }
    }

    /** User tapped "Open Odin Settings" in the handoff dialog. */
    fun confirmHandoff() {
        viewModelScope.launch {
            settingsStorage.persistOdinHandoffTutorialSeen()
        }
        odinScriptHandoff.launchOdinSettings()
        handoffRequest.value = null
    }

    /** User cancelled the handoff dialog. */
    fun dismissHandoff() {
        viewModelScope.launch {
            settingsStorage.persistOdinHandoffTutorialSeen()
        }
        val previous = handoffRequest.value
        handoffRequest.value = null
        if (previous != null) {
            transientMessage.value = "Apply not completed. Tap a preset and try again."
        }
    }

    /** Called from onResume after the user returned from Odin Settings. */
    fun verifyAfterHandoff() {
        val current = state.value
        if (current.policies.isEmpty()) return
        viewModelScope.launch {
            repository.refreshLiveValues()
            val mismatches = current.policies.mapNotNull { policy ->
                val requested = current.currentValues[policy.id] ?: return@mapNotNull null
                val actual = current.actualValues[policy.id] ?: return@mapNotNull null
                if (ProfileStateResolver.isPolicyValueSatisfied(
                        policy = policy,
                        requestedValue = requested,
                        actualValue = actual,
                    )
                ) null else policy.id
            }
            transientMessage.value = if (mismatches.isEmpty()) {
                "Underclock applied via Odin Settings"
            } else {
                "Apply did not land; check Odin Settings ran the script"
            }
        }
    }



    fun createUserProfile(name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = null, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.createUserProfile(trimmedName, state.currentValues)
            transientMessage.value = "Saved profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun updateProfile(profileId: String, name: String, state: TunerState) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            transientError.value = "Profile name is required"
            return
        }
        viewModelScope.launch {
            if (hasDuplicateProfileName(trimmedName, excludedId = profileId, state = state)) {
                transientError.value = "Profile name already exists"
                return@launch
            }
            repository.updateProfile(profileId, trimmedName, state.currentValues)
            transientMessage.value = "Updated profile \"$trimmedName\""
            transientError.value = null
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            repository.deleteProfile(profileId)
            transientMessage.value = "Deleted profile"
            transientError.value = null
        }
    }

    fun moveProfile(profileId: String, offset: Int) {
        viewModelScope.launch {
            repository.moveProfile(profileId, offset)
        }
    }

    fun resetProfilesToDefault() {
        viewModelScope.launch {
            repository.resetProfilesToDefault()
            transientMessage.value = "Restored bundled profiles and removed custom profiles"
            transientError.value = null
        }
    }

    suspend fun exportProfilesJson(): String {
        return repository.exportProfilesJson()
    }

    suspend fun importProfilesJson(rawJson: String): Int {
        val importedCount = repository.importProfilesJson(rawJson)
        transientMessage.value = if (importedCount == 1) {
            "Imported 1 profile"
        } else {
            "Imported $importedCount profiles"
        }
        transientError.value = null
        return importedCount
    }

    fun setTileTapBehavior(behavior: TileInteractionBehavior, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistTileTapBehavior(behavior)
            onSaved()
        }
    }

    fun setApplyLastProfileOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistApplyLastProfileOnBoot(enabled)
        }
    }

    fun setColorSource(colorSource: AppColorSource) {
        viewModelScope.launch {
            settingsStorage.persistColorSource(colorSource)
        }
    }

    fun setAccentColor(accentColor: Int) {
        viewModelScope.launch {
            settingsStorage.persistAccentColor(accentColor)
        }
    }

    fun refreshLiveState() {
        repository.refreshLiveValues()
    }

    private fun snapToSupported(policy: CpuPolicyInfo, rawValue: Int): Int {
        return policy.supportedFrequencies.minByOrNull { supported -> abs(supported - rawValue) }
            ?: rawValue
    }

    private fun hasDuplicateProfileName(
        name: String,
        excludedId: String?,
        state: TunerState,
    ): Boolean {
        return state.displayProfiles
            .filter { profile -> profile.source != ProfileSource.VIRTUAL }
            .any { profile ->
                profile.id != excludedId && profile.name.equals(name, ignoreCase = true)
            }
    }

    private fun buildAppliedMessage(
        appliedProfile: PerformanceProfile?,
        commandOutput: String?,
    ): String {
        val base = if (appliedProfile != null) {
            "Applied profile: ${appliedProfile.name}"
        } else {
            "Applied profile: Manual"
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
            "C${policy.id} requested ${formatFrequency(requested)}, " +
                "actual ${formatFrequency(actual, boosted = actual > policy.selectableMaxFreq)}"
        }
        val base = "Apply did not stick: $summary"
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    companion object {
        private const val LOG_TAG = "ClusterTuneApply"

        fun factory(
            repository: PerformanceRepository,
            settingsStorage: SettingsStorage,
            odinScriptHandoff: OdinScriptHandoff,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TunerViewModel(repository, settingsStorage, odinScriptHandoff) as T
                }
            }
        }
    }
}
