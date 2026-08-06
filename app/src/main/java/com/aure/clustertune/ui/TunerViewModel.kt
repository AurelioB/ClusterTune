package com.aure.clustertune.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aure.clustertune.data.InstalledAppRepository
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.InstalledAppInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.root.PrivilegedExecutionResolver
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
    private val privilegedExecutionResolver: PrivilegedExecutionResolver,
    private val installedAppRepository: InstalledAppRepository,
) : ViewModel() {

    private val edits = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val transientMessage = MutableStateFlow<String?>(null)
    private val transientError = MutableStateFlow<String?>(null)
    private val installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val recentApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())

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

    val launchableApps: StateFlow<List<InstalledAppInfo>> = installedApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val recentActiveApps: StateFlow<List<InstalledAppInfo>> = recentApps.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    init {
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch {
            installedApps.value = installedAppRepository.listLaunchableApps()
            recentApps.value = installedAppRepository.listRecentActiveApps()
        }
    }

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

    /** Discards values staged by a compact tuner without changing the applied state. */
    fun discardEdits() {
        edits.value = emptyMap()
        transientMessage.value = null
        transientError.value = null
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
            val applyResult = repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
            )
            applyResult.onSuccess { outcome ->
                edits.value = emptyMap()
                transientMessage.value = if (outcome.verificationPassed) {
                    buildAppliedMessage(appliedProfile, outcome.commandOutput)
                } else {
                    buildVerificationFailureMessage(state, outcome.actualValues, outcome.commandOutput)
                }
                transientError.value = null
            }.onFailure { throwable ->
                transientError.value = throwable.message ?: "Failed to apply limits"
            }
            if (applyResult.isSuccess) {
                repository.selectProfile(appliedProfile?.id?.takeUnless { it == ProfileStateResolver.STOCK_PROFILE_ID })
                repository.logProfileSwitch(
                    profileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                    profileName = appliedProfile?.name ?: "Manual",
                    trigger = "Manual apply from Profiles tab",
                )
                onApplied(appliedProfile?.name ?: "Custom values")
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

    fun saveAppProfileAssignment(
        packageName: String,
        appLabel: String,
        profileId: String?,
        customMaxFrequencies: Map<Int, Int> = emptyMap(),
    ) {
        viewModelScope.launch {
            repository.saveAppProfileAssignment(
                AppProfileAssignment(
                    packageName = packageName,
                    appLabel = appLabel,
                    profileId = profileId,
                    customMaxFrequencies = customMaxFrequencies,
                ),
            )
            transientMessage.value = "Saved app profile for $appLabel"
            transientError.value = null
        }
    }

    suspend fun saveAppProfileAssignmentAwait(
        packageName: String,
        appLabel: String,
        profileId: String?,
        customMaxFrequencies: Map<Int, Int> = emptyMap(),
    ) {
        repository.saveAppProfileAssignment(
            AppProfileAssignment(packageName, appLabel, profileId, customMaxFrequencies),
        )
    }

    suspend fun deleteAppProfileAssignmentAwait(packageName: String) {
        repository.deleteAppProfileAssignment(packageName)
    }

    suspend fun applyAppProfileTemporarily(assignment: AppProfileAssignment): Result<PerformanceRepository.ApplyOutcome> =
        repository.applyAppProfileTemporarily(assignment)

    suspend fun logProfileSwitchAwait(profileId: String?, profileName: String, trigger: String) {
        repository.logProfileSwitch(profileId, profileName, trigger)
    }

    fun deleteAppProfileAssignment(packageName: String) {
        viewModelScope.launch {
            repository.deleteAppProfileAssignment(packageName)
            transientMessage.value = "Deleted app profile"
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

    fun setSleepProfileEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileEnabled(enabled)
            onSaved()
        }
    }

    fun configureSleepProfile(enabled: Boolean, profileId: String?, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfile(enabled, profileId)
            onSaved()
        }
    }

    fun setSleepProfile(profileId: String?) {
        viewModelScope.launch {
            settingsStorage.persistSleepProfileId(profileId)
        }
    }

    fun setColorSource(colorSource: AppColorSource) {
        viewModelScope.launch {
            settingsStorage.persistColorSource(colorSource)
        }
    }

    fun setAccentColor(accentColor: Int) {
        viewModelScope.launch {
            settingsStorage.persistPresetAccentColor(accentColor)
        }
    }

    fun setCustomAccentColor(accentColor: Int) {
        viewModelScope.launch {
            settingsStorage.persistCustomAccentColor(accentColor)
        }
    }

    fun setAutomaticUpdateChecksEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistAutomaticUpdateChecksEnabled(enabled)
        }
    }

    fun setUpdateCheckIntervalDays(days: Int) {
        viewModelScope.launch {
            settingsStorage.persistUpdateCheckIntervalDays(days)
        }
    }

    fun setIncludePrereleaseUpdates(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistIncludePrereleaseUpdates(enabled)
        }
    }

    fun setDisplayFrequenciesAsPercent(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistDisplayFrequenciesAsPercent(enabled)
        }
    }

    fun setLeftEdgeProfilePickerEnabled(enabled: Boolean, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            settingsStorage.persistLeftEdgeProfilePickerEnabled(enabled)
            onSaved()
        }
    }

    fun setEdgeHandleHeightDp(heightDp: Int) {
        viewModelScope.launch {
            settingsStorage.persistEdgeHandleHeightDp(heightDp)
        }
    }

    fun setEdgeHandleThicknessDp(thicknessDp: Int) {
        viewModelScope.launch {
            settingsStorage.persistEdgeHandleThicknessDp(thicknessDp)
        }
    }

    fun setEdgeHandleVerticalPositionPercent(positionPercent: Int) {
        viewModelScope.launch {
            settingsStorage.persistEdgeHandleVerticalPositionPercent(positionPercent)
        }
    }

    fun setEdgeHandleOpacityPercent(opacityPercent: Int) {
        viewModelScope.launch {
            settingsStorage.persistEdgeHandleOpacityPercent(opacityPercent)
        }
    }

    fun setProfileSwitchToastsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStorage.persistProfileSwitchToastsEnabled(enabled)
        }
    }

    fun setProfileSwitchHistoryLimit(limit: Int) {
        viewModelScope.launch {
            settingsStorage.persistProfileSwitchHistoryLimit(limit)
            repository.trimProfileSwitchHistory(limit)
        }
    }

    fun setPrivilegedExecutionMethod(methodId: String?) {
        viewModelScope.launch {
            privilegedExecutionResolver.setConfiguredMethodId(methodId)
            settingsStorage.persistPrivilegedExecutionMethodId(methodId)
            transientMessage.value = methodId
                ?.let { "Privileged method set to ${formatExecutionMethod(it)}" }
                ?: "Privileged method set to automatic"
            transientError.value = null
        }
    }

    fun autoDetectPrivilegedExecutionMethod() {
        viewModelScope.launch {
            val methodId = privilegedExecutionResolver.autoDetectBestMethod(forceReprobe = true)
            settingsStorage.persistPrivilegedExecutionMethodId(methodId)
            transientMessage.value = methodId
                ?.let { "Using ${formatExecutionMethod(it)}" }
                ?: "No privileged execution method is available"
            transientError.value = null
        }
    }

    fun refreshLiveState() {
        repository.refreshLiveValues()
    }

    /**
     * Re-probes which privileged execution method is available and refreshes
     * live state so the UI reflects it. Used by the no-root wireless-debugging
     * flow: connecting/disconnecting changes availability, and the main screen
     * needs to switch between the profile list and the setup prompt.
     *
     * Unlike [autoDetectPrivilegedExecutionMethod] this does not persist the
     * detected id or post a user-facing message — it is a silent re-check.
     */
    fun recheckExecutionAvailability() {
        viewModelScope.launch {
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.d("recheckExecutionAvailability: called")
            val id = privilegedExecutionResolver.autoDetectBestMethod(forceReprobe = true)
            com.wuyr.jdwp_injector.debug.JdwpDebugLog.d(
                "recheckExecutionAvailability: detected=${id ?: "null"}",
            )
            repository.refreshLiveValues()
        }
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
        return appliedProfile?.name ?: "Custom values"
    }

    private fun buildVerificationFailureMessage(
        state: TunerState,
        actualValues: Map<Int, Int>,
        commandOutput: String?,
    ): String {
        val summary = state.policies.joinToString(", ") { policy ->
            val requested = state.currentValues[policy.id] ?: policy.currentMaxFreq
            val actual = actualValues[policy.id] ?: policy.currentMaxFreq
            "C${policy.id} requested ${formatFrequency(requested, policy = policy, displayAsPercent = settings.value.displayFrequenciesAsPercent)}, " +
                "actual ${formatFrequency(actual, boosted = actual > policy.selectableMaxFreq, policy = policy, displayAsPercent = settings.value.displayFrequenciesAsPercent)}"
        }
        val base = "Apply did not stick: $summary"
        return commandOutput?.takeIf { it.isNotBlank() }?.let { "$base | log: ${it.take(120)}" } ?: base
    }

    private fun formatExecutionMethod(methodId: String): String {
        return executionMethodLabel(methodId)
    }

    companion object {
        fun factory(
            repository: PerformanceRepository,
            settingsStorage: SettingsStorage,
            privilegedExecutionResolver: PrivilegedExecutionResolver,
            installedAppRepository: InstalledAppRepository,
        ): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TunerViewModel(
                        repository,
                        settingsStorage,
                        privilegedExecutionResolver,
                        installedAppRepository,
                    ) as T
                }
            }
        }
    }
}
