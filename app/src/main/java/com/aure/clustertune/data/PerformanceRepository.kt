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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal data class ResolvedPerformanceTarget(val values: Map<Int, Int>, val profileId: String?, val isReset: Boolean)

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
        const val SYSFS_REPAIR_VERSION = 1
        @Volatile
        private var processCachedPolicies: List<CpuPolicyInfo> = emptyList()
        private val processApplyMutex = Mutex()
        // The loop exits as soon as the read-back matches, so a longer schedule
        // costs nothing on root/PServer (which match on attempt 0). It matters
        // for the JDWP path, which is fire-and-forget: the injected exec returns
        // before the script has written sysfs, so the old ~160ms window reported
        // a false "Apply did not stick".
        private const val APPLY_VERIFICATION_ATTEMPTS = 12
        private const val APPLY_VERIFICATION_DELAY_MS = 120L
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
                    val liveMinValues = detector.readCurrentMinValues(cachedPolicies)
                    cachedPolicies.map { policy ->
                        policy.copy(
                            currentMaxFreq = liveValues[policy.id] ?: policy.currentMaxFreq,
                            minFreq = liveMinValues[policy.id] ?: policy.minFreq,
                        )
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

    /** Repairs stale minimum nodes left locked or raised by older releases. */
    suspend fun repairSysfsMinimumsIfNeeded(): Result<Unit> {
        if (profileStorage.sysfsRepairVersion.first() >= SYSFS_REPAIR_VERSION) return Result.success(Unit)
        return processApplyMutex.withLock {
            // Multiple services can create their own AppContainer at startup.
            // Recheck after taking the process-wide lock so only one migration runs.
            if (profileStorage.sysfsRepairVersion.first() >= SYSFS_REPAIR_VERSION) {
                return@withLock Result.success(Unit)
            }
            val policies = detector.detectPolicies()
            if (policies.isEmpty() || policies.any { it.hardwareMinFreq <= 0 }) {
                return@withLock Result.failure(IllegalStateException("CPU policy minimums unavailable"))
            }
            val execution = rootCommandRunner.executeScript(commandBuilder.buildMinimumRepairScript(policies))
            if (execution.isFailure) return@withLock execution.map { Unit }
            val currentMax = detector.readCurrentMaxValues(policies)
            val currentMin = detector.readCurrentMinValues(policies)
            val verified = policies.all { policy ->
                val min = currentMin[policy.id]
                val max = currentMax[policy.id]
                min != null && min > 0 && max != null && min <= max
            }
            if (!verified) return@withLock Result.failure(IllegalStateException("CPU minimum repair verification failed"))
            profileStorage.markSysfsRepairComplete(SYSFS_REPAIR_VERSION)
            Result.success(Unit)
        }
    }

    private suspend fun applyValuesInternal(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
        allowObservedMaxValues: Boolean = false,
    ): Result<ApplyOutcome> {
        return processApplyMutex.withLock {
            applyValuesLocked(policies, selectedValues, isReset, appliedDisplayProfileId, persistNormalState, allowObservedMaxValues)
        }
    }

    private suspend fun applyValuesLocked(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
        allowObservedMaxValues: Boolean = false,
    ): Result<ApplyOutcome> {
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        if (!isCompleteValidValues(filtered, policies, isReset || allowObservedMaxValues)) {
            return Result.failure(IllegalArgumentException("Invalid CPU policy values"))
        }
        val script = runCatching {
            commandBuilder.buildApplyScript(policies, filtered, isReset)
        }.getOrElse { error ->
            return Result.failure(error)
        }
        if (script.isBlank()) return Result.failure(IllegalStateException("Empty CPU apply script"))
        return rootCommandRunner.executeScript(script).mapCatching { output ->
            var actualValues = emptyMap<Int, Int>()
            var actualMinValues = emptyMap<Int, Int>()
            var verified = false
            for (attempt in 0 until APPLY_VERIFICATION_ATTEMPTS) {
                if (attempt > 0) delay(APPLY_VERIFICATION_DELAY_MS)
                actualValues = detector.readCurrentMaxValues(policies)
                actualMinValues = detector.readCurrentMinValues(policies)
                val maxesMatch = filtered.all { (policyId, requestedValue) ->
                    val policy = policies.firstOrNull { it.id == policyId } ?: return@all false
                    val actualValue = actualValues[policyId] ?: return@all false
                    actualValue == requestedValue || (
                        (isReset || allowObservedMaxValues) &&
                            ProfileStateResolver.isPolicyValueSatisfied(policy, requestedValue, actualValue)
                    )
                }
                val minsMatch = policies.all { policy ->
                    val actualMin = actualMinValues[policy.id] ?: return@all false
                    val actualMax = actualValues[policy.id] ?: return@all false
                    actualMin > 0 && actualMin <= actualMax &&
                        actualMin <= (filtered[policy.id] ?: actualMax)
                }
                if (maxesMatch && minsMatch) {
                    verified = true
                    break
                }
            }
            if (!verified) {
                throw IllegalStateException(
                    "CPU policy verification failed: max=$actualValues min=$actualMinValues",
                )
            }
            if (persistNormalState) {
                val completeValues = policies.associate { policy -> policy.id to filtered.getValue(policy.id) }
                profileStorage.persistNormalProfileState(completeValues, appliedDisplayProfileId)
            }
            refreshLiveValues()
            ApplyOutcome(
                actualValues = actualValues,
                verificationPassed = true,
                commandOutput = output,
            )
        }
    }


    suspend fun applySleepProfile(profileId: String): Result<ApplyOutcome> {
        if (!rootCommandRunner.isAvailable) {
            return Result.failure(IllegalStateException("PServer not available"))
        }
        val resultAndProfile = processApplyMutex.withLock {
            val state = observeState().first()
            if (state.policies.isEmpty()) return@withLock null
            val sleepProfile = state.displayProfiles.firstOrNull { profile -> profile.id == profileId }
                ?: return@withLock null
            ensureNormalBaselineLocked(state)
            val currentValues = detector.readCurrentMaxValues(state.policies)
            // SCREEN_OFF can be delivered repeatedly while the display is
            // transitioning. Preserve the first pre-sleep snapshot until the
            // corresponding wake restore consumes it.
            if (profileStorage.sleepRestoreValues.first().isEmpty()) {
                profileStorage.persistSleepRestoreState(currentValues, state.activeDisplayProfileId ?: state.lastAppliedDisplayProfileId)
            }
            applyValuesLocked(state.policies, sleepProfile.maxFrequencies, sleepProfile.id == ProfileStateResolver.STOCK_PROFILE_ID, sleepProfile.id, false) to sleepProfile
        } ?: return Result.failure(IllegalStateException("Sleep profile is unavailable"))
        val result = resultAndProfile.first
        val sleepProfile = resultAndProfile.second
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
        val resultAndName = processApplyMutex.withLock {
            val restoreValues = profileStorage.sleepRestoreValues.first()
            // A wake broadcast can be delivered more than once. The snapshot
            // is consumed only after a successful restore, so an empty value
            // set means there is no pending sleep transition to restore. Do
            // not fall back to the normal profile on a duplicate wake: that
            // could overwrite a profile selected after the first wake.
            if (restoreValues.isEmpty()) return@withLock null
            val restoreProfileId = profileStorage.sleepRestoreDisplayProfileId.first()
            val state = observeState().first()
            val target = resolvePersistedTarget(policies, state.displayProfiles, restoreProfileId, restoreValues)
                ?: return@withLock null
            val result = applyValuesLocked(
                policies,
                target.values,
                target.isReset,
                target.profileId,
                false,
                allowObservedMaxValues = allowsObservedMaxValues(target, policies),
            )
                .onSuccess { profileStorage.clearSleepRestoreState() }
            result to target.profileId
        } ?: return Result.failure(IllegalStateException("No valid sleep restore state"))
        val result = resultAndName.first
        val restoreProfileId = resultAndName.second
        val restoreProfileName = when (restoreProfileId) {
            ProfileStateResolver.STOCK_PROFILE_ID -> "Stock"
            null,
            ProfileStateResolver.MANUAL_PROFILE_ID -> "Manual"
            else -> observeState().first().displayProfiles.firstOrNull { profile ->
                profile.id == restoreProfileId
            }?.name ?: "Previous profile"
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
        return processApplyMutex.withLock {
            val state = observeState().first()
            ensureNormalBaselineLocked(state.copy(policies = policies))
            val persistedValues = profileStorage.lastValues.first()
            val lastAppliedDisplayProfileId = profileStorage.lastAppliedDisplayProfileId.first()
            val target = resolvePersistedTarget(
                policies,
                state.displayProfiles,
                lastAppliedDisplayProfileId,
                persistedValues,
            ) ?: return@withLock Result.failure(IllegalStateException("No valid stored values to apply"))
            if (target.profileId == ProfileStateResolver.STOCK_PROFILE_ID) {
                return@withLock Result.failure(IllegalStateException("Boot apply skipped: stock is active"))
            }
            applyValuesLocked(
                policies,
                target.values,
                target.isReset,
                target.profileId,
                true,
                allowObservedMaxValues = allowsObservedMaxValues(target, policies),
            )
        }
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
        return processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPServerAvailable || state.policies.isEmpty()) return@withLock Result.failure(IllegalStateException("Profile automation is unavailable"))
            ensureNormalBaselineLocked(state)
            val profile = state.displayProfiles.firstOrNull { it.id == profileId }
                ?: return@withLock Result.failure(IllegalStateException("App profile is unavailable"))
            applyValuesLocked(state.policies, profile.maxFrequencies, profile.id == ProfileStateResolver.STOCK_PROFILE_ID, profile.id, false)
        }
    }

    suspend fun applyAppProfileTemporarily(assignment: AppProfileAssignment): Result<ApplyOutcome> {
        return processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPServerAvailable || state.policies.isEmpty()) return@withLock Result.failure(IllegalStateException("Profile automation is unavailable"))
            ensureNormalBaselineLocked(state)
            val values = if (assignment.isCustom) assignment.customMaxFrequencies else {
                val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId }
                    ?: return@withLock Result.failure(IllegalStateException("App profile is unavailable"))
                profile.maxFrequencies
            }
            val filteredValues = values.filterKeys { policyId -> state.policies.any { it.id == policyId } }
            if (filteredValues.isEmpty()) return@withLock Result.failure(IllegalStateException("App profile has no matching CPU policies"))
            val completeValues = if (assignment.isCustom) {
                mergeCustomValues(state.policies, filteredValues, profileStorage.lastValues.first())
                    ?: return@withLock Result.failure(IllegalStateException("App profile has invalid CPU policies"))
            } else filteredValues
            applyValuesLocked(state.policies, completeValues, !assignment.isCustom && assignment.profileId == ProfileStateResolver.STOCK_PROFILE_ID, assignment.profileId, false, assignment.isCustom)
        }
    }

    suspend fun restoreNormalProfileTemporarily(): Result<ApplyOutcome> {
        return processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPServerAvailable || state.policies.isEmpty()) return@withLock Result.failure(IllegalStateException("Profile automation is unavailable"))
            ensureNormalBaselineLocked(state)
            val id = profileStorage.lastAppliedDisplayProfileId.first()
            val values = profileStorage.lastValues.first()
            val target = resolvePersistedTarget(state.policies, state.displayProfiles, id, values)
                ?: return@withLock Result.failure(IllegalStateException("No previous profile to restore"))
            applyValuesLocked(
                state.policies,
                target.values,
                target.isReset,
                target.profileId,
                false,
                allowObservedMaxValues = allowsObservedMaxValues(target, state.policies),
            )
        }
    }

    private suspend fun ensureNormalBaselineLocked(state: TunerState) {
        val values = profileStorage.lastValues.first()
        val id = profileStorage.lastAppliedDisplayProfileId.first()
        val resolved = resolvePersistedTarget(state.policies, state.displayProfiles, id, values)
        if (resolved != null) {
            if (resolved.values != values || resolved.profileId != id) {
                profileStorage.persistNormalProfileState(resolved.values, resolved.profileId)
            }
            return
        }
        // Live values can be a temporary app-profile cap that survived an
        // update or process restart. Do not infer a named/manual baseline from
        // those values when persisted state is missing or invalid. Re-establish
        // a deterministic Stock target from observed hardware ceilings,
        // including bins hidden from the selectable-frequency list.
        val fallback = resolveLegacyStockBaseline(state.policies)
        profileStorage.persistNormalProfileState(fallback.values, fallback.profileId)
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

private fun isCompleteValidValues(values: Map<Int, Int>, policies: List<CpuPolicyInfo>, isReset: Boolean = false): Boolean =
    policies.isNotEmpty() && policies.all { policy ->
        val value = values[policy.id]
        value != null && (value in policy.supportedFrequencies || (isReset && value == policy.observedMaxFreq))
    }

internal fun mergeCustomValues(
    policies: List<CpuPolicyInfo>, customValues: Map<Int, Int>, baselineValues: Map<Int, Int>,
): Map<Int, Int>? {
    if (customValues.keys.any { id -> policies.none { it.id == id } }) return null
    val merged = policies.associate { policy ->
        val custom = customValues[policy.id]
        if (custom != null) {
            if (custom !in policy.supportedFrequencies) return null
            policy.id to custom
        } else {
            val baseline = baselineValues[policy.id] ?: return null
            if (baseline !in policy.supportedFrequencies && baseline != policy.observedMaxFreq) return null
            policy.id to baseline
        }
    }
    return merged
}

internal fun resolvePersistedTarget(
    policies: List<CpuPolicyInfo>, profiles: List<PerformanceProfile>, profileId: String?, values: Map<Int, Int>,
): ResolvedPerformanceTarget? {
    val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
    fun normalized(source: Map<Int, Int>): Map<Int, Int> = policies.associate { it.id to source.getValue(it.id) }
    if (profile != null) {
        val reset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID
        return profile.maxFrequencies.takeIf { isCompleteValidValues(it, policies, reset) }
            ?.let { ResolvedPerformanceTarget(normalized(it), profile.id, reset) }
    }
    if (profileId != null && profileId != ProfileStateResolver.MANUAL_PROFILE_ID) {
        if (!isCompleteValidValues(values, policies, true)) return null
        return ResolvedPerformanceTarget(normalized(values), ProfileStateResolver.MANUAL_PROFILE_ID, false)
    }
    return values.takeIf { isCompleteValidValues(it, policies, true) }
        ?.let { ResolvedPerformanceTarget(normalized(it), ProfileStateResolver.MANUAL_PROFILE_ID, false) }
}

/**
 * Returns the safe baseline used to repair missing or invalid legacy state.
 * Stock is defined by the observed policy ceiling, rather than the current
 * sysfs value, which may still reflect a temporary app-profile cap.
 */
internal fun resolveLegacyStockBaseline(policies: List<CpuPolicyInfo>): ResolvedPerformanceTarget {
    val values = policies.associate { policy -> policy.id to policy.observedMaxFreq }
    return ResolvedPerformanceTarget(
        values = values,
        profileId = ProfileStateResolver.STOCK_PROFILE_ID,
        isReset = true,
    )
}

internal fun allowsObservedMaxValues(target: ResolvedPerformanceTarget, policies: List<CpuPolicyInfo>): Boolean =
    target.values.any { (policyId, value) ->
        policies.firstOrNull { it.id == policyId }?.let { policy ->
            value == policy.observedMaxFreq && value !in policy.supportedFrequencies
        } == true
    }
