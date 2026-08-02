package com.aure.clustertune.data

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MIN_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.root.PerformanceCommandBuilder
import com.aure.clustertune.root.RootCommandRunner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import java.util.UUID

private data class StorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
    val appProfileAssignments: List<AppProfileAssignment>,
    val profileSwitchHistory: List<ProfileSwitchHistoryEntry>,
    val selectedProfileId: String?,
    val lastAppliedDisplayProfileId: String?,
)

private data class PartialStorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
    val appProfileAssignments: List<AppProfileAssignment>,
    val profileSwitchHistory: List<ProfileSwitchHistoryEntry>,
)

internal data class ImportedProfileMerge(
    val profiles: List<PerformanceProfile>,
    val restoredBundledProfileIds: Set<String>,
)

class PerformanceRepository(
    private val detector: CpuPolicyDetector,
    private val bundledProfileProvider: BundledProfileProvider,
    private val profileStorage: ProfileStorage,
    private val settingsStorage: SettingsStorage,
    private val commandBuilder: PerformanceCommandBuilder,
    private val rootCommandRunner: RootCommandRunner,
) {
    companion object {
        @Volatile
        private var processCachedPolicies: List<CpuPolicyInfo> = emptyList()
    }

    data class ApplyOutcome(
        val actualValues: Map<Int, Int>,
        val verificationPassed: Boolean,
        val commandOutput: String?,
    )

    private val liveRefreshToken = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeState(): Flow<TunerState> {
        val storageBaseState = combine(
            profileStorage.profiles,
            profileStorage.deletedBundledProfileIds,
            profileStorage.displayOrder,
            profileStorage.lastValues,
            profileStorage.appProfileAssignments,
        ) { storedProfiles, deletedBundledProfileIds, displayOrder, lastValues, appProfileAssignments ->
            PartialStorageState(
                storedProfiles = storedProfiles,
                deletedBundledProfileIds = deletedBundledProfileIds,
                displayOrder = displayOrder,
                lastValues = lastValues,
                appProfileAssignments = appProfileAssignments,
                profileSwitchHistory = emptyList(),
            )
        }
        val storageState = combine(
            storageBaseState,
            profileStorage.profileSwitchHistory,
        ) { storage, profileSwitchHistory ->
            storage.copy(profileSwitchHistory = profileSwitchHistory)
        }
        val completeStorageState = combine(
            storageState,
            profileStorage.selectedProfileId,
            profileStorage.lastAppliedDisplayProfileId,
        ) { partial, selectedProfileId, lastAppliedDisplayProfileId ->
            StorageState(
                storedProfiles = partial.storedProfiles,
                deletedBundledProfileIds = partial.deletedBundledProfileIds,
                displayOrder = partial.displayOrder,
                lastValues = partial.lastValues,
                appProfileAssignments = partial.appProfileAssignments,
                profileSwitchHistory = partial.profileSwitchHistory,
                selectedProfileId = selectedProfileId,
                lastAppliedDisplayProfileId = lastAppliedDisplayProfileId,
            )
        }
        return combine(
            liveRefreshToken,
            completeStorageState,
        ) { _, storage -> storage }
            .transformLatest { storage ->
                val cachedPolicies = processCachedPolicies
                val policies = if (cachedPolicies.isEmpty()) {
                    detector.detectPolicies().also { detectedPolicies ->
                        processCachedPolicies = detectedPolicies
                    }
                } else {
                    val liveValues = detector.readCurrentMaxValues(cachedPolicies)
                    cachedPolicies.map { policy ->
                        policy.copy(currentMaxFreq = liveValues[policy.id] ?: policy.currentMaxFreq)
                    }
                }
                val actualValues = policies.associate { it.id to it.currentMaxFreq }
                val defaultBundledProfiles = bundledProfileProvider.createProfiles(policies)
                val storedById = storage.storedProfiles.associateBy { it.id }
                val knownBundledIds = defaultBundledProfiles.map { it.id }.toSet()
                val bundledProfiles = defaultBundledProfiles.mapIndexed { index, profile ->
                    if (profile.id in storage.deletedBundledProfileIds) {
                        null
                    } else {
                        val stored = storedById[profile.id]
                        if (stored != null) {
                            profile.copy(
                                name = stored.name,
                                maxFrequencies = stored.maxFrequencies,
                                order = stored.order,
                                isEditable = true,
                                isDeletable = true,
                            )
                        } else {
                            profile.copy(
                                order = index,
                                isEditable = true,
                                isDeletable = true,
                            )
                        }
                    }
                }.filterNotNull()
                val userProfiles = storage.storedProfiles
                    .filter { it.source == ProfileSource.USER && it.id !in knownBundledIds }
                val orderedRealProfiles = applyDisplayOrder(
                    profiles = bundledProfiles + userProfiles,
                    orderedIds = storage.displayOrder,
                )
                val defaultValues = policies.associate { it.id to it.currentMaxFreq }
                val stockProfile = ProfileStateResolver.buildStockProfile(policies)
                emit(
                    ProfileStateResolver.resolve(
                        TunerState(
                            isLoading = false,
                            isPServerAvailable = rootCommandRunner.isAvailable,
                            privilegedExecutionMethodId = rootCommandRunner.selectedExecutionMethodId,
                            policies = policies,
                            actualValues = actualValues,
                            currentValues = mergeValues(policies, defaultValues, storage.lastValues),
                            bundledProfiles = orderedRealProfiles.filter { it.source == ProfileSource.BUNDLED },
                            userProfiles = orderedRealProfiles.filter { it.source == ProfileSource.USER },
                            selectedProfileId = storage.selectedProfileId?.takeIf { id ->
                                orderedRealProfiles.any { it.id == id }
                            },
                            lastAppliedDisplayProfileId = storage.lastAppliedDisplayProfileId,
                            displayProfiles = ProfileStateResolver.buildDisplayProfiles(
                                realProfiles = orderedRealProfiles,
                                stockProfile = stockProfile,
                                orderedIds = storage.displayOrder,
                            ),
                            appProfileAssignments = supportedAppProfileAssignments(
                                assignments = storage.appProfileAssignments,
                                realProfiles = orderedRealProfiles,
                            ),
                            profileSwitchHistory = storage.profileSwitchHistory,
                        ),
                    ),
                )
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
    ): Result<ApplyOutcome> {
        return applyValuesInternal(
            policies = policies,
            selectedValues = selectedValues,
            isReset = isReset,
            appliedDisplayProfileId = appliedDisplayProfileId,
            persistNormalState = true,
        )
    }

    private suspend fun applyValuesInternal(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
    ): Result<ApplyOutcome> {
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        val script = commandBuilder.buildApplyScript(policies, filtered, isReset)
        return rootCommandRunner.executeScript(script).mapCatching { output ->
            if (persistNormalState) {
                profileStorage.persistLastValues(filtered)
                profileStorage.persistLastAppliedDisplayProfile(appliedDisplayProfileId)
            }
            val actualValues = detector.readCurrentMaxValues(policies)
            refreshLiveValues()
            ApplyOutcome(
                actualValues = actualValues,
                verificationPassed = filtered.all { (policyId, requestedValue) ->
                    val policy = policies.firstOrNull { it.id == policyId } ?: return@all false
                    val actualValue = actualValues[policyId] ?: return@all false
                    ProfileStateResolver.isPolicyValueSatisfied(
                        policy = policy,
                        requestedValue = requestedValue,
                        actualValue = actualValue,
                    )
                },
                commandOutput = output,
            )
        }
    }

    suspend fun applySleepProfile(profileId: String): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val state = observeState().first()
        if (state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val sleepProfile = state.displayProfiles.firstOrNull { profile -> profile.id == profileId }
            ?: return Result.failure(IllegalStateException("Sleep profile is unavailable"))
        val currentValues = detector.readCurrentMaxValues(state.policies)
        profileStorage.persistSleepRestoreState(
            values = currentValues,
            profileId = state.activeDisplayProfileId ?: state.lastAppliedDisplayProfileId,
        )
        val result = applyValuesInternal(
            policies = state.policies,
            selectedValues = sleepProfile.maxFrequencies,
            isReset = sleepProfile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = sleepProfile.id,
            persistNormalState = false,
        )
        if (result.isSuccess) {
            logProfileSwitch(
                profileId = sleepProfile.id,
                profileName = sleepProfile.name,
                trigger = "Device sleep",
            )
        }
        return result
    }

    suspend fun restorePreSleepState(): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = detector.detectPolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val restoreValues = profileStorage.sleepRestoreValues.first()
        if (restoreValues.isEmpty()) {
            return Result.failure(IllegalStateException("No sleep restore state"))
        }
        val restoreProfileId = profileStorage.sleepRestoreDisplayProfileId.first()
        val filteredValues = restoreValues.filterKeys { policyId ->
            policies.any { it.id == policyId }
        }
        if (filteredValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values match detected policies"))
        }
        val restoreProfileName = when (restoreProfileId) {
            ProfileStateResolver.STOCK_PROFILE_ID -> "Stock"
            null,
            ProfileStateResolver.MANUAL_PROFILE_ID -> "Manual"
            else -> observeState().first().displayProfiles.firstOrNull { profile ->
                profile.id == restoreProfileId
            }?.name ?: "Previous profile"
        }
        val result = applyValuesInternal(
            policies = policies,
            selectedValues = filteredValues,
            isReset = restoreProfileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = restoreProfileId,
            persistNormalState = true,
        ).onSuccess {
            profileStorage.persistSelectedProfile(
                restoreProfileId?.takeUnless { id ->
                    id == ProfileStateResolver.STOCK_PROFILE_ID || id == ProfileStateResolver.MANUAL_PROFILE_ID
                },
            )
            profileStorage.clearSleepRestoreState()
        }
        if (result.isSuccess) {
            logProfileSwitch(
                profileId = restoreProfileId ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                profileName = restoreProfileName,
                trigger = "Device wake",
            )
        }
        return result
    }

    suspend fun applyPersistedLastValuesOnBoot(): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val policies = detector.detectPolicies()
        if (policies.isEmpty()) {
            return Result.failure(IllegalStateException("No CPU clusters found"))
        }
        val persistedValues = profileStorage.lastValues.first()
        if (persistedValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values to apply"))
        }
        val lastAppliedDisplayProfileId = profileStorage.lastAppliedDisplayProfileId.first()
        if (lastAppliedDisplayProfileId == ProfileStateResolver.STOCK_PROFILE_ID) {
            return Result.failure(IllegalStateException("Boot apply skipped: stock is active"))
        }
        val filteredValues = persistedValues.filterKeys { policyId ->
            policies.any { it.id == policyId }
        }
        if (filteredValues.isEmpty()) {
            return Result.failure(IllegalStateException("No stored values match detected policies"))
        }
        return applyValues(
            policies = policies,
            selectedValues = filteredValues,
            isReset = false,
            appliedDisplayProfileId = lastAppliedDisplayProfileId,
        )
    }

    suspend fun cycleTileProfile(): Result<PerformanceProfile> {
        val state = observeState().first()
        if (!state.isPServerAvailable || state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("Tile controls are unavailable"))
        }

        val cycleProfiles = state.displayProfiles.filter { profile ->
            profile.source != ProfileSource.VIRTUAL || profile.id == ProfileStateResolver.STOCK_PROFILE_ID
        }
        if (cycleProfiles.isEmpty()) {
            return Result.failure(IllegalStateException("No profiles available for tile cycling"))
        }

        val currentProfileId = state.lastAppliedDisplayProfileId
            ?.takeIf { id -> cycleProfiles.any { profile -> profile.id == id } }
            ?: state.activeDisplayProfileId
        val currentIndex = cycleProfiles.indexOfFirst { it.id == currentProfileId }
        val nextProfile = if (currentIndex == -1) {
            cycleProfiles.first()
        } else {
            cycleProfiles[(currentIndex + 1) % cycleProfiles.size]
        }

        return applyValues(
            policies = state.policies,
            selectedValues = nextProfile.maxFrequencies,
            isReset = nextProfile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = nextProfile.id,
        ).map {
            selectProfile(nextProfile.id.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID })
            logProfileSwitch(
                profileId = nextProfile.id,
                profileName = nextProfile.name,
                trigger = "Quick Settings tile",
            )
            nextProfile
        }
    }

    suspend fun createUserProfile(name: String, values: Map<Int, Int>) {
        val currentProfiles = realProfiles()
        profileStorage.saveProfile(
            PerformanceProfile(
                id = "user_${UUID.randomUUID()}",
                name = name,
                maxFrequencies = values,
                source = ProfileSource.USER,
                order = currentProfiles.size,
            ),
        )
    }

    suspend fun exportProfilesJson(): String {
        val profiles = realProfiles()
            .filter { profile -> profile.source != ProfileSource.VIRTUAL }
            .mapIndexed { index, profile ->
                profile.copy(
                    order = index,
                    isEditable = true,
                    isDeletable = true,
                )
            }
        return ProfileJsonCodec.encodeShareFile(
            profiles = profiles,
            socModel = bundledProfileProvider.currentSocModel(),
        )
    }

    suspend fun importProfilesJson(rawJson: String): Int {
        val state = observeState().first()
        val policyIds = state.policies.associateBy { it.id }
        val currentProfiles = state.displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
        val defaultBundledProfiles = bundledProfileProvider.createProfiles(state.policies)
        val validProfiles = ProfileJsonCodec.parseShareProfiles(rawJson)
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() &&
                    profile.maxFrequencies.all { (policyId, frequency) ->
                        val policy = policyIds[policyId] ?: return@all false
                        frequency in policy.supportedFrequencies
                    }
            }

        val merge = mergeImportedProfiles(
            currentProfiles = currentProfiles,
            defaultBundledProfiles = defaultBundledProfiles,
            importedProfiles = validProfiles,
        )
        merge.restoredBundledProfileIds.forEach { bundledProfileId ->
            profileStorage.unmarkBundledProfileDeleted(bundledProfileId)
        }
        merge.profiles.forEach { profile ->
            profileStorage.saveProfile(profile)
        }
        return validProfiles.size
    }

    suspend fun updateProfile(profileId: String, name: String, values: Map<Int, Int>) {
        val existing = realProfiles().firstOrNull { it.id == profileId }
            ?: return
        if (existing.source == ProfileSource.BUNDLED) {
            profileStorage.unmarkBundledProfileDeleted(profileId)
        }
        profileStorage.saveProfile(
            existing.copy(
                name = name,
                maxFrequencies = values,
            ),
        )
    }

    suspend fun deleteProfile(profileId: String) {
        val existing = realProfiles().firstOrNull { it.id == profileId } ?: return
        if (existing.source == ProfileSource.BUNDLED) {
            profileStorage.markBundledProfileDeleted(profileId)
        } else {
            profileStorage.deleteProfile(profileId)
        }
        if (profileStorage.selectedProfileId.first() == profileId) {
            profileStorage.persistSelectedProfile(null)
        }
    }

    suspend fun saveAppProfileAssignment(assignment: AppProfileAssignment) {
        if (assignment.packageName.isBlank() || !assignment.hasValidTarget) return
        val state = observeState().first()
        if (assignment.isCustom) {
            val validValues = assignment.customMaxFrequencies.filter { (policyId, frequency) ->
                state.policies.firstOrNull { it.id == policyId }?.supportedFrequencies?.contains(frequency) == true
            }
            if (validValues.isEmpty()) return
            profileStorage.saveAppProfileAssignment(
                assignment.copy(
                    appLabel = assignment.appLabel.ifBlank { assignment.packageName },
                    profileId = null,
                    customMaxFrequencies = validValues,
                ),
            )
            return
        }
        val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId } ?: return
        profileStorage.saveAppProfileAssignment(
            assignment.copy(
                appLabel = assignment.appLabel.ifBlank { assignment.packageName },
                profileId = profile.id,
                customMaxFrequencies = emptyMap(),
            ),
        )
    }

    suspend fun deleteAppProfileAssignment(packageName: String) {
        profileStorage.deleteAppProfileAssignment(packageName)
    }

    suspend fun applyProfileTemporarily(profileId: String): Result<ApplyOutcome> {
        val state = observeState().first()
        if (!state.isPServerAvailable || state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("Profile automation is unavailable"))
        }
        val profile = state.displayProfiles.firstOrNull { it.id == profileId }
            ?: return Result.failure(IllegalStateException("App profile is unavailable"))
        return applyValuesInternal(
            policies = state.policies,
            selectedValues = profile.maxFrequencies,
            isReset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profile.id,
            persistNormalState = false,
        )
    }

    suspend fun applyAppProfileTemporarily(assignment: AppProfileAssignment): Result<ApplyOutcome> {
        val state = observeState().first()
        if (!state.isPServerAvailable || state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("Profile automation is unavailable"))
        }
        val values = if (assignment.isCustom) {
            assignment.customMaxFrequencies
        } else {
            val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId }
                ?: return Result.failure(IllegalStateException("App profile is unavailable"))
            profile.maxFrequencies
        }
        val filteredValues = values.filterKeys { policyId -> state.policies.any { it.id == policyId } }
        if (filteredValues.isEmpty()) {
            return Result.failure(IllegalStateException("App profile has no matching CPU policies"))
        }
        return applyValuesInternal(
            policies = state.policies,
            selectedValues = filteredValues,
            isReset = !assignment.isCustom && assignment.profileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = assignment.profileId,
            persistNormalState = false,
        )
    }

    suspend fun restoreNormalProfileTemporarily(): Result<ApplyOutcome> {
        val state = observeState().first()
        if (!state.isPServerAvailable || state.policies.isEmpty()) {
            return Result.failure(IllegalStateException("Profile automation is unavailable"))
        }
        val restoreProfileId = profileStorage.lastAppliedDisplayProfileId.first()
        val restoreProfile = restoreProfileId?.let { id ->
            state.displayProfiles.firstOrNull { it.id == id }
        }
        val restoreValues = restoreProfile?.maxFrequencies
            ?: profileStorage.lastValues.first().takeIf { it.isNotEmpty() }
            ?: return Result.failure(IllegalStateException("No previous profile to restore"))
        return applyValuesInternal(
            policies = state.policies,
            selectedValues = restoreValues,
            isReset = restoreProfileId == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = restoreProfileId,
            persistNormalState = false,
        )
    }

    suspend fun logProfileSwitch(profileId: String?, profileName: String, trigger: String) {
        val limit = settingsStorage.settings.first().profileSwitchHistoryLimit
            .coerceIn(MIN_PROFILE_SWITCH_HISTORY_LIMIT, MAX_PROFILE_SWITCH_HISTORY_LIMIT)
        profileStorage.appendProfileSwitchHistory(
            ProfileSwitchHistoryEntry(
                timestampMillis = System.currentTimeMillis(),
                profileId = profileId,
                profileName = profileName,
                trigger = trigger,
            ),
            limit = limit,
        )
    }

    suspend fun trimProfileSwitchHistory(limit: Int = DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT) {
        profileStorage.trimProfileSwitchHistory(
            limit.coerceIn(MIN_PROFILE_SWITCH_HISTORY_LIMIT, MAX_PROFILE_SWITCH_HISTORY_LIMIT),
        )
    }

    suspend fun clearProfileSwitchHistory() {
        profileStorage.clearProfileSwitchHistory()
    }

    suspend fun moveProfile(profileId: String, offset: Int) {
        val state = observeState().first()
        val profiles = state.displayProfiles.toMutableList()
        val currentIndex = profiles.indexOfFirst { it.id == profileId }
        if (currentIndex == -1) return
        val targetIndex = (currentIndex + offset).coerceIn(0, profiles.lastIndex)
        if (currentIndex == targetIndex) return
        val profile = profiles.removeAt(currentIndex)
        profiles.add(targetIndex, profile)
        profileStorage.persistDisplayOrder(profiles.map { it.id })
        profileStorage.replaceProfiles(
            profiles
                .filter { it.source != ProfileSource.VIRTUAL }
                .mapIndexed { index, realProfile -> realProfile.copy(order = index) },
        )
    }

    suspend fun resetProfilesToDefault() {
        profileStorage.resetProfiles()
        profileStorage.persistSelectedProfile(null)
    }

    suspend fun selectProfile(profileId: String?) {
        profileStorage.persistSelectedProfile(profileId)
    }

    fun refreshLiveValues() {
        liveRefreshToken.update { it + 1 }
    }

    private fun mergeValues(
        policies: List<CpuPolicyInfo>,
        currentValues: Map<Int, Int>,
        persistedValues: Map<Int, Int>,
    ): Map<Int, Int> {
        return policies.associate { policy ->
            val supported = policy.supportedFrequencies.toSet()
            val persisted = persistedValues[policy.id]
            val safeValue = if (persisted != null && persisted in supported) {
                persisted
            } else {
                currentValues[policy.id] ?: policy.currentMaxFreq
            }
            policy.id to safeValue
        }
    }

    private suspend fun realProfiles(): List<PerformanceProfile> {
        val state = observeState().first()
        return state.displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
    }

    private fun applyDisplayOrder(
        profiles: List<PerformanceProfile>,
        orderedIds: List<String>,
    ): List<PerformanceProfile> {
        if (orderedIds.isEmpty()) return profiles.sortedBy { it.order }
        val byId = profiles.associateBy { it.id }
        val ordered = orderedIds.mapNotNull(byId::get)
        val missing = profiles.filter { it.id !in orderedIds }.sortedBy { it.order }
        return ordered + missing
    }

}

internal fun mergeImportedProfiles(
    currentProfiles: List<PerformanceProfile>,
    defaultBundledProfiles: List<PerformanceProfile>,
    importedProfiles: List<PerformanceProfile>,
): ImportedProfileMerge {
    val currentById = currentProfiles.associateBy { it.id }
    val defaultBundledById = defaultBundledProfiles.associateBy { it.id }
    val restoredBundledProfileIds = mutableSetOf<String>()
    var nextNewProfileOrder = currentProfiles.size

    val profiles = importedProfiles.map { importedProfile ->
        val bundledProfile = defaultBundledById[importedProfile.id]
        if (bundledProfile != null) {
            restoredBundledProfileIds += bundledProfile.id
            val existing = currentById[importedProfile.id] ?: bundledProfile
            existing.copy(
                name = importedProfile.name,
                maxFrequencies = importedProfile.maxFrequencies,
                source = ProfileSource.BUNDLED,
                isEditable = true,
                isDeletable = true,
            )
        } else {
            val existing = currentById[importedProfile.id]
            existing?.copy(
                name = importedProfile.name,
                maxFrequencies = importedProfile.maxFrequencies,
                source = ProfileSource.USER,
                isEditable = true,
                isDeletable = true,
            )
                ?: importedProfile.copy(
                    source = ProfileSource.USER,
                    order = nextNewProfileOrder++,
                    isEditable = true,
                    isDeletable = true,
                )
        }
    }

    return ImportedProfileMerge(
        profiles = profiles,
        restoredBundledProfileIds = restoredBundledProfileIds,
    )
}

internal fun supportedAppProfileAssignments(
    assignments: List<AppProfileAssignment>,
    realProfiles: List<PerformanceProfile>,
): List<AppProfileAssignment> {
    val supportedProfileIds = realProfiles.mapTo(mutableSetOf()) { profile -> profile.id }
    supportedProfileIds += ProfileStateResolver.STOCK_PROFILE_ID
    val supportedPolicyIds = realProfiles.flatMapTo(mutableSetOf()) { profile -> profile.maxFrequencies.keys }
    return assignments.filter { assignment ->
        assignment.hasValidTarget &&
            ((assignment.isCustom && assignment.customMaxFrequencies.keys.all { it in supportedPolicyIds }) ||
                assignment.profileId in supportedProfileIds)
    }
}
