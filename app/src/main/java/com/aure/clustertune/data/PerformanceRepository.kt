package com.aure.clustertune.data

import android.util.Log
import com.aure.clustertune.apps.CombinedAppProfileResolver
import com.aure.clustertune.apps.VisibleAppProfileTarget
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MIN_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.model.EffectiveProfileSource
import com.aure.clustertune.model.EffectiveProfileState
import com.aure.clustertune.root.host.ApplyRequest
import com.aure.clustertune.root.host.ClusterTuneHostClient
import com.aure.clustertune.root.host.HostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

private data class StorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
    val lastGpuValue: Int?,
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
    val lastGpuValue: Int?,
    val appProfileAssignments: List<AppProfileAssignment>,
    val profileSwitchHistory: List<ProfileSwitchHistoryEntry>,
)

internal data class ResolvedPerformanceTarget(val values: Map<Int, Int>, val profileId: String?, val isReset: Boolean, val gpuValue: Int? = null)

private data class SleepRestoreApply(
    val result: Result<PerformanceRepository.ApplyOutcome>,
    val profileId: String?,
    val effectiveProfileState: EffectiveProfileState?,
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
    private val gpuDetector: GpuPolicyDetector? = null,
    private val hostClient: ClusterTuneHostClient,
) {
    class SupersededManualApplyException : Exception("Manual apply superseded")

    companion object {
        @Volatile
        private var processCachedPolicies: List<CpuPolicyInfo> = emptyList()
        private val processApplyMutex = Mutex()
        private val manualRequestSequence = AtomicLong(0L)

        fun allocateManualRequestToken(): Long = manualRequestSequence.incrementAndGet()

        fun isManualRequestCurrent(token: Long): Boolean = manualRequestSequence.get() == token

        private fun isLatestManualRequest(token: Long): Boolean =
            isManualRequestCurrent(token)
    }

    data class ApplyOutcome(
        val actualValues: Map<Int, Int>,
        val verificationPassed: Boolean,
        val commandOutput: String?,
        val actualGpuMaxFrequencyHz: Int? = null,
    )

    data class AppProfileApplyOutcome(
        val hardware: ApplyOutcome,
        val profileId: String,
        val profileName: String,
        val contributingPackages: List<String>,
        val isCombined: Boolean,
    )

    data class TemporaryRestoreOutcome(
        val hardware: ApplyOutcome,
        val profileId: String,
        val profileName: String,
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
                lastGpuValue = null,
                appProfileAssignments = appProfileAssignments,
                profileSwitchHistory = emptyList(),
            )
        }
        val storageState = combine(
            storageBaseState,
            profileStorage.profileSwitchHistory,
            profileStorage.lastGpuValue,
        ) { storage, profileSwitchHistory, lastGpuValue ->
            storage.copy(
                profileSwitchHistory = profileSwitchHistory,
                lastGpuValue = lastGpuValue,
            )
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
                lastGpuValue = partial.lastGpuValue,
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
                val host = readHostSnapshot()
                val cachedPolicies = processCachedPolicies
                val policies = host?.first ?: if (cachedPolicies.isEmpty()) {
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
                val actualValues = host?.second?.cpuMax?.mapIndexedNotNull { index, value ->
                    policies.getOrNull(index)?.id?.let { it to value.toIntChecked() }
                }?.toMap()
                    ?: policies.associate { it.id to it.currentMaxFreq }
                val gpuReader = gpuDetector
                val gpuPolicy = host?.third ?: gpuReader?.detectPolicy()
                val actualGpu = host?.second?.gpuMax?.toIntChecked()
                    ?: gpuPolicy?.let { gpuReader?.readCurrentMaxFrequency(it) }
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
                                // Bundled profiles historically omitted the
                                // optional GPU field. For those profiles the
                                // omission means Stock; user profiles keep a
                                // null GPU as an intentional wildcard.
                                gpuMaxFrequencyHz = stored.gpuMaxFrequencyHz
                                    ?: profile.gpuMaxFrequencyHz
                                    ?: if (profile.source == ProfileSource.BUNDLED) gpuPolicy?.observedMaxFrequencyHz else null,
                                order = stored.order,
                                isEditable = true,
                                isDeletable = true,
                            )
                        } else {
                            profile.copy(
                                order = index,
                                isEditable = true,
                                isDeletable = true,
                                gpuMaxFrequencyHz = profile.gpuMaxFrequencyHz
                                    ?: if (profile.source == ProfileSource.BUNDLED) gpuPolicy?.observedMaxFrequencyHz else null,
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
                val stockProfile = ProfileStateResolver.buildStockProfile(policies, gpuPolicy)
                val currentGpu = storage.lastGpuValue
                    ?.takeIf { value -> gpuPolicy?.let { isValidGpuValue(it, value) } == true }
                    ?: actualGpu
                emit(
                    ProfileStateResolver.resolve(
                        TunerState(
                            isLoading = false,
                            // A successfully queried host is itself proof that a
                            // privileged executor is available. This keeps a
                            // warm host usable while the resolver refreshes its
                            // selected-method state.
                            isPrivilegedHostAvailable = host != null,
                            privilegedExecutionMethodId = hostClient.selectedMethodId,
                            policies = policies,
                            actualValues = actualValues,
                            gpuPolicy = gpuPolicy,
                            actualGpuMaxFrequencyHz = actualGpu,
                            currentGpuMaxFrequencyHz = currentGpu,
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
        manualRequestToken: Long? = null,
        onHardwareApplied: (suspend (ApplyOutcome) -> Unit)? = null,
    ): Result<ApplyOutcome> {
        return applyValuesInternal(
            policies = policies,
            selectedValues = selectedValues,
            isReset = isReset,
            appliedDisplayProfileId = appliedDisplayProfileId,
            persistNormalState = true,
            gpuPolicy = null,
            selectedGpuMaxFrequencyHz = null,
            manualRequestToken = manualRequestToken,
            onHardwareApplied = onHardwareApplied,
        )
    }

    suspend fun applyValues(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        gpuPolicy: GpuPolicyInfo?,
        selectedGpuMaxFrequencyHz: Int?,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        manualRequestToken: Long? = null,
        onHardwareApplied: (suspend (ApplyOutcome) -> Unit)? = null,
    ): Result<ApplyOutcome> = applyValuesInternal(
        policies = policies,
        selectedValues = selectedValues,
        isReset = isReset,
        appliedDisplayProfileId = appliedDisplayProfileId,
        persistNormalState = true,
        gpuPolicy = gpuPolicy,
        selectedGpuMaxFrequencyHz = selectedGpuMaxFrequencyHz,
        manualRequestToken = manualRequestToken,
        onHardwareApplied = onHardwareApplied,
    )

    private fun clientSnapshotOrThrow() = hostClient.readSnapshot()

    private suspend fun applyValuesInternal(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
        allowObservedMaxValues: Boolean = false,
        gpuPolicy: GpuPolicyInfo? = null,
        selectedGpuMaxFrequencyHz: Int? = null,
        manualRequestToken: Long? = null,
        onHardwareApplied: (suspend (ApplyOutcome) -> Unit)? = null,
    ): Result<ApplyOutcome> {
        return processApplyMutex.withLock {
            if (manualRequestToken != null && !isLatestManualRequest(manualRequestToken)) {
                return@withLock Result.failure(SupersededManualApplyException())
            }
            applyValuesLocked(policies, selectedValues, isReset, appliedDisplayProfileId, persistNormalState, allowObservedMaxValues, gpuPolicy, selectedGpuMaxFrequencyHz, manualRequestToken, onHardwareApplied)
        }
    }

    private suspend fun applyValuesLocked(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
        appliedDisplayProfileId: String?,
        persistNormalState: Boolean,
        allowObservedMaxValues: Boolean = false,
        gpuPolicy: GpuPolicyInfo? = null,
        selectedGpuMaxFrequencyHz: Int? = null,
        manualRequestToken: Long? = null,
        onHardwareApplied: (suspend (ApplyOutcome) -> Unit)? = null,
    ): Result<ApplyOutcome> {
        // A persisted GPU value can outlive GPU support (for example after
        // importing a profile on a CPU-only device). Never let that stale
        // value force a host failure or get dispatched to an unavailable domain.
        val effectiveGpuTarget = selectedGpuMaxFrequencyHz.takeIf { gpuPolicy != null }
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        if (!isCompleteValidValues(filtered, policies, isReset || allowObservedMaxValues)) {
            return Result.failure(IllegalArgumentException("Invalid CPU policy values"))
        }
        if (gpuPolicy != null && effectiveGpuTarget != null &&
            !isValidGpuValue(gpuPolicy, effectiveGpuTarget)) {
            return Result.failure(IllegalArgumentException("Invalid GPU policy value"))
        }
        // A newer manual request may have arrived while this request was
        // validating its inputs under the process-wide mutex. Recheck before
        // entering the host call so stale requests never start a Binder
        // transaction.
        if (manualRequestToken != null && !isLatestManualRequest(manualRequestToken)) {
            return Result.failure(SupersededManualApplyException())
        }
        return run {
            val client = hostClient
            val hostResult = withContext(Dispatchers.IO) { runCatching {
                val appliedState = client.applyProfile(
                    ApplyRequest(
                        cpuMax = policies.map { filtered.getValue(it.id).toLong() },
                        gpuMax = effectiveGpuTarget?.toLong(),
                        resetToStock = isReset,
                        cpuIds = policies.map { "policy${it.id}" },
                        gpuId = effectiveGpuTarget?.let { gpuPolicy?.policyPath?.substringAfterLast('/') },
                        gpuMaxPath = effectiveGpuTarget?.let { gpuPolicy?.maxFrequencyPath },
                        stabilizedStockCeiling = effectiveGpuTarget?.takeIf {
                            gpuPolicy?.let { gpu ->
                                it == gpu.observedMaxFrequencyHz && gpu.observedMaxFrequencyHz > gpu.selectableMaxFrequencyHz
                            } == true
                        }?.toLong(),
                    ),
                ).getOrThrow()
                val actual = appliedState.cpuMax.mapIndexed { index, value -> policies[index].id to value.toIntChecked() }.toMap()
                val actualGpu = appliedState.gpuMax?.toIntChecked()
                ApplyOutcome(actual, true, null, actualGpu)
            } }
            val outcome = hostResult.getOrElse { return Result.failure(it) }
            refreshLiveValues()
            if (manualRequestToken == null || isLatestManualRequest(manualRequestToken)) {
                onHardwareApplied?.invoke(outcome)
            }
            if (persistNormalState) {
                if (manualRequestToken != null && !isLatestManualRequest(manualRequestToken)) {
                    return Result.failure(SupersededManualApplyException())
                }
                runCatching {
                    profileStorage.persistNormalProfileState(
                        policies.associate { it.id to filtered.getValue(it.id) },
                        appliedDisplayProfileId,
                        outcome.actualGpuMaxFrequencyHz,
                        appliedDisplayProfileId?.takeUnless {
                            it == ProfileStateResolver.STOCK_PROFILE_ID || it == ProfileStateResolver.MANUAL_PROFILE_ID
                        },
                    )
                }.onFailure { error ->
                    Log.e("PerformanceRepository", "Hardware apply succeeded but persistence failed", error)
                }
            }
            Result.success(outcome)
        }
    }

    private suspend fun readHostSnapshot(): Triple<List<CpuPolicyInfo>, HostState, GpuPolicyInfo?>? {
        val client = hostClient
        return runCatching {
            val snapshot = client.readSnapshot().getOrThrow()
            val capabilities = snapshot.capabilities
            val state = snapshot.state
            require(
                capabilities.cpus.isNotEmpty() &&
                    state.cpuMax.size == capabilities.cpus.size &&
                    state.cpuMin.size == capabilities.cpus.size,
            ) { "Host returned an incomplete CPU snapshot" }
            val policies = capabilities.cpus.mapIndexed { index, domain ->
                val id = domain.id.removePrefix("policy").toIntOrNull()
                    ?: error("Invalid host CPU id: ${domain.id}")
                val currentMax = state.cpuMax[index].toIntChecked()
                val currentMin = state.cpuMin[index].toIntChecked()
                val selectableMax = domain.selectableMax.toIntChecked()
                val supported = domain.supportedFrequencies
                    .map { it.toIntChecked() }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                    .ifEmpty {
                        listOf(currentMin, currentMax, selectableMax).distinct().sorted()
                    }
                val minimumCandidates = domain.minimumCandidates
                    .map { it.toIntChecked() }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                    .ifEmpty { listOf(currentMin) }
                CpuPolicyInfo(
                    id = id,
                    policyPath = domain.maxPath.substringBeforeLast('/'),
                    scalingMaxPath = domain.maxPath,
                    currentMaxFreq = currentMax,
                    selectableMaxFreq = selectableMax,
                    observedMaxFreq = domain.observedMax.toIntChecked(),
                    minFreq = currentMin,
                    supportedFrequencies = supported,
                    cpuIds = listOf(id),
                    scalingMinPath = domain.minPath,
                    hardwareMinFreq = minimumCandidates.first(),
                    minimumCandidates = minimumCandidates,
                )
            }
            val gpu = capabilities.gpu?.let { domain ->
                val currentMax = state.gpuMax?.toIntChecked() ?: domain.currentMax.toIntChecked()
                // Some firmware reports a transiently capped selectable value
                // while the stock ceiling remains available in stabilized
                // capability data. Keep the writable threshold separate so
                // Stock can still use the hidden observed ceiling.
                val observedMax = maxOf(
                    gpuDetector?.stabilizeObservedCeiling(domain.maxPath, domain.observedMax.toIntChecked())
                        ?: domain.observedMax.toIntChecked(),
                    domain.stockMax.takeIf { it > 0L }?.toIntChecked() ?: 0,
                )
                // selectableMax is the host's advertised writable threshold.
                // Keep hidden stock/observed ceilings separate so callers can
                // still request Stock without expanding the slider domain.
                val selectableMax = domain.selectableMax.toIntChecked()
                val supported = domain.supportedFrequencies
                    .map { it.toIntChecked() }
                    .filter { it > 0 }
                    .distinct()
                    .sorted()
                    .ifEmpty { listOf(currentMax, selectableMax).distinct().sorted() }
                GpuPolicyInfo(
                    policyPath = domain.id,
                    maxFrequencyPath = domain.maxPath,
                    currentFrequencyPath = domain.curPath,
                    minFrequencyPath = domain.minPath,
                    currentMaxFrequencyHz = currentMax,
                    selectableMaxFrequencyHz = selectableMax,
                    observedMaxFrequencyHz = observedMax,
                    supportedFrequenciesHz = supported,
                    hardwareMinFrequencyHz = domain.observedMin.takeIf { it > 0 }?.toIntChecked(),
                )
            }
            Triple(policies, state, gpu)
        }.getOrNull()
    }


    suspend fun applySleepProfile(profileId: String): Result<ApplyOutcome> {
        val resultAndProfile = processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPrivilegedHostAvailable) {
                return@withLock Pair<Result<ApplyOutcome>, PerformanceProfile?>(
                    Result.failure(IllegalStateException("Privileged host unavailable")),
                    null,
                )
            }
            if (state.policies.isEmpty()) return@withLock null
            val sleepProfile = state.displayProfiles.firstOrNull { profile -> profile.id == profileId }
                ?: return@withLock null
            ensureNormalBaselineLocked(state)
            val currentValues = state.actualValues
            val gpuPolicy = state.gpuPolicy
            val currentGpu = state.actualGpuMaxFrequencyHz
            // SCREEN_OFF can be delivered repeatedly while the display is
            // transitioning. Preserve the first pre-sleep snapshot until the
            // corresponding wake restore consumes it.
            if (profileStorage.sleepRestoreValues.first().isEmpty()) {
                profileStorage.persistSleepRestoreState(
                    currentValues,
                    state.activeDisplayProfileId ?: state.lastAppliedDisplayProfileId,
                    currentGpu,
                    gpuKnown = gpuPolicy != null && currentGpu != null,
                    effectiveProfileState = profileStorage.effectiveProfileState.first(),
                )
            }
            val sleepGpuTarget = if (currentGpu == null) null else sleepProfile.gpuMaxFrequencyHz
            applyValuesLocked(state.policies, sleepProfile.maxFrequencies, sleepProfile.id == ProfileStateResolver.STOCK_PROFILE_ID, sleepProfile.id, false, gpuPolicy = gpuPolicy, selectedGpuMaxFrequencyHz = sleepGpuTarget) to sleepProfile
        } ?: return Result.failure(IllegalStateException("Sleep profile is unavailable"))
        val result = resultAndProfile.first
        val sleepProfile = resultAndProfile.second ?: return result
        if (result.isSuccess) {
            logProfileSwitch(
                profileId = sleepProfile.id,
                profileName = sleepProfile.name,
                trigger = "Device sleep",
                effectiveSource = EffectiveProfileSource.SLEEP,
            )
        }
        return result
    }

    suspend fun restorePreSleepState(): Result<ApplyOutcome> {
        val resultAndIdentity = processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPrivilegedHostAvailable) {
                return@withLock SleepRestoreApply(
                    Result.failure(IllegalStateException("Privileged host unavailable")),
                    null,
                    null,
                )
            }
            val policies = state.policies
            if (policies.isEmpty()) return@withLock null
            val restoreValues = profileStorage.sleepRestoreValues.first()
            // A wake broadcast can be delivered more than once. The snapshot
            // is consumed only after a successful restore, so an empty value
            // set means there is no pending sleep transition to restore. Do
            // not fall back to the normal profile on a duplicate wake: that
            // could overwrite a profile selected after the first wake.
            if (restoreValues.isEmpty()) return@withLock null
            val restoreProfileId = profileStorage.sleepRestoreDisplayProfileId.first()
            val restoreEffectiveState = profileStorage.sleepRestoreEffectiveProfileState.first()
            val restoreGpu = profileStorage.sleepRestoreGpuValue.first()
            val restoreGpuKnown = profileStorage.sleepRestoreGpuKnown.first()
            // A known GPU domain with no snapshotted value is unresolved, not
            // a wildcard. Keep the snapshot so a later capability refresh can
            // restore it instead of stranding the sleep cap as "completed".
            if (restoreGpuKnown && (state.gpuPolicy == null || restoreGpu == null)) {
                return@withLock SleepRestoreApply(
                    Result.failure(IllegalStateException("GPU restore state is unavailable")),
                    null,
                    restoreEffectiveState,
                )
            }
            val target = resolvePersistedTarget(policies, state.displayProfiles, restoreProfileId, restoreValues, restoreGpu)
                ?: return@withLock null
            val result = applyValuesLocked(
                policies,
                target.values,
                target.isReset,
                target.profileId,
                false,
                allowObservedMaxValues = allowsObservedMaxValues(target, policies),
                gpuPolicy = state.gpuPolicy,
                selectedGpuMaxFrequencyHz = restoreGpu,
            )
                .onSuccess { profileStorage.clearSleepRestoreState() }
            SleepRestoreApply(result, target.profileId, restoreEffectiveState)
        } ?: return Result.failure(IllegalStateException("No valid sleep restore state"))
        val result = resultAndIdentity.result
        val restoreProfileId = resultAndIdentity.profileId
        val persistedEffective = resultAndIdentity.effectiveProfileState
        val restoreProfileName = persistedEffective?.name ?: when (restoreProfileId) {
            ProfileStateResolver.STOCK_PROFILE_ID -> "Stock"
            null,
            ProfileStateResolver.MANUAL_PROFILE_ID -> "Manual"
            else -> observeState().first().displayProfiles.firstOrNull { profile ->
                profile.id == restoreProfileId
            }?.name ?: "Previous profile"
        }
        if (result.isSuccess) {
            logProfileSwitch(
                profileId = persistedEffective?.id ?: restoreProfileId ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                profileName = restoreProfileName,
                trigger = "Device wake",
                effectiveSource = persistedEffective?.source ?: when (restoreProfileId) {
                    ProfileStateResolver.STOCK_PROFILE_ID -> EffectiveProfileSource.STOCK
                    ProfileStateResolver.MANUAL_PROFILE_ID, null -> EffectiveProfileSource.MANUAL
                    else -> EffectiveProfileSource.NORMAL
                },
                contributingPackageNames = persistedEffective?.contributingPackageNames.orEmpty(),
            )
        }
        return result
    }

    suspend fun applyPersistedLastValuesOnBoot(): Result<ApplyOutcome> {
        var effectiveIdentity: Pair<String, String>? = null
        val result = processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPrivilegedHostAvailable) {
                return@withLock Result.failure(IllegalStateException("Privileged host unavailable"))
            }
            val policies = state.policies
            if (policies.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("No CPU clusters found"))
            }
            ensureNormalBaselineLocked(state)
            val persistedValues = profileStorage.lastValues.first()
            val lastAppliedDisplayProfileId = profileStorage.lastAppliedDisplayProfileId.first()
            val target = resolvePersistedTarget(
                policies,
                state.displayProfiles,
                lastAppliedDisplayProfileId,
                persistedValues,
                profileStorage.lastGpuValue.first(),
                gpuStockFallback = state.gpuPolicy?.observedMaxFrequencyHz,
            ) ?: return@withLock Result.failure(IllegalStateException("No valid stored values to apply"))
            if (target.profileId == ProfileStateResolver.STOCK_PROFILE_ID) {
                // Stock is already the device baseline; still refresh the
                // persisted identity so the tile cannot retain a stale app
                // override after a reboot.
                effectiveIdentity = ProfileStateResolver.STOCK_PROFILE_ID to "Stock"
                return@withLock Result.success(
                    ApplyOutcome(
                        actualValues = state.actualValues,
                        verificationPassed = true,
                        commandOutput = null,
                        actualGpuMaxFrequencyHz = state.actualGpuMaxFrequencyHz,
                    ),
                )
            }
            effectiveIdentity = target.profileId?.let { id ->
                id to (state.displayProfiles.firstOrNull { profile -> profile.id == id }?.name ?: "Manual")
            } ?: (ProfileStateResolver.MANUAL_PROFILE_ID to "Manual")
            applyValuesLocked(
                policies,
                target.values,
                target.isReset,
                target.profileId,
                true,
                allowObservedMaxValues = allowsObservedMaxValues(target, policies),
                gpuPolicy = state.gpuPolicy,
                selectedGpuMaxFrequencyHz = target.gpuValue,
            )
        }
        if (result.isSuccess) {
            val (id, name) = effectiveIdentity ?: (ProfileStateResolver.MANUAL_PROFILE_ID to "Manual")
            profileStorage.persistEffectiveProfileState(
                EffectiveProfileState(
                    id = id,
                    name = name,
                    source = when (id) {
                        ProfileStateResolver.STOCK_PROFILE_ID -> EffectiveProfileSource.STOCK
                        ProfileStateResolver.MANUAL_PROFILE_ID -> EffectiveProfileSource.MANUAL
                        else -> EffectiveProfileSource.NORMAL
                    },
                ),
            )
        }
        return result
    }

    suspend fun cycleTileProfile(): Result<PerformanceProfile> {
        val state = observeState().first()
        if (!state.isPrivilegedHostAvailable || state.policies.isEmpty()) {
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
            gpuPolicy = state.gpuPolicy,
            selectedGpuMaxFrequencyHz = nextProfile.gpuMaxFrequencyHz,
            isReset = nextProfile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = nextProfile.id,
        ).map {
            selectProfile(nextProfile.id.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID })
            logProfileSwitch(
                profileId = nextProfile.id,
                profileName = nextProfile.name,
                trigger = "Quick Settings tile",
                effectiveSource = if (nextProfile.id == ProfileStateResolver.STOCK_PROFILE_ID) {
                    EffectiveProfileSource.STOCK
                } else {
                    EffectiveProfileSource.NORMAL
                },
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

    suspend fun createUserProfile(name: String, values: Map<Int, Int>, gpuMaxFrequencyHz: Int?) {
        val currentProfiles = realProfiles()
        profileStorage.saveProfile(PerformanceProfile("user_${UUID.randomUUID()}", name, values, source = ProfileSource.USER, order = currentProfiles.size, gpuMaxFrequencyHz = gpuMaxFrequencyHz))
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
        val gpuPolicy = state.gpuPolicy
        val validProfiles = ProfileJsonCodec.parseShareProfiles(rawJson)
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() &&
                    profile.maxFrequencies.all { (policyId, frequency) ->
                        val policy = policyIds[policyId] ?: return@all false
                        frequency in policy.supportedFrequencies
                    } && (profile.gpuMaxFrequencyHz == null || (gpuPolicy != null && profile.gpuMaxFrequencyHz.let { it in gpuPolicy.supportedFrequenciesHz || it == gpuPolicy.observedMaxFrequencyHz }))
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

    suspend fun updateProfile(profileId: String, name: String, values: Map<Int, Int>, gpuMaxFrequencyHz: Int?) {
        val existing = realProfiles().firstOrNull { it.id == profileId } ?: return
        if (existing.source == ProfileSource.BUNDLED) profileStorage.unmarkBundledProfileDeleted(profileId)
        profileStorage.saveProfile(existing.copy(name = name, maxFrequencies = values, gpuMaxFrequencyHz = gpuMaxFrequencyHz))
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
        // The event-driven app-profile coordinator consumes assignments
        // directly, so never leave an assignment pointing at a deleted
        // profile.
        profileStorage.deleteAppProfileAssignmentsForProfile(profileId)
    }

    suspend fun saveAppProfileAssignment(assignment: AppProfileAssignment) {
        if (assignment.packageName.isBlank() || !assignment.hasValidTarget) return
        val state = observeState().first()
        if (assignment.isCustom) {
            val validValues = assignment.customMaxFrequencies.filter { (policyId, frequency) ->
                state.policies.firstOrNull { it.id == policyId }?.supportedFrequencies?.contains(frequency) == true
            }
            val validGpu = assignment.customGpuMaxFrequencyHz?.takeIf { value ->
                state.gpuPolicy?.let { isValidGpuValue(it, value) } == true
            }
            if (assignment.customGpuMaxFrequencyHz != null && validGpu == null) return
            if (validValues.isEmpty() && validGpu == null) return
            profileStorage.saveAppProfileAssignment(
                assignment.copy(
                    appLabel = assignment.appLabel.ifBlank { assignment.packageName },
                    profileId = null,
                    customMaxFrequencies = validValues,
                    customGpuMaxFrequencyHz = validGpu,
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
                customGpuMaxFrequencyHz = null,
            ),
        )
    }

    suspend fun deleteAppProfileAssignment(packageName: String) {
        profileStorage.deleteAppProfileAssignment(packageName)
    }

    /** Applies the least restrictive envelope requested by every assigned app currently visible. */
    suspend fun applyVisibleAppProfilesTemporarily(
        assignments: List<AppProfileAssignment>,
    ): Result<AppProfileApplyOutcome> {
        return processApplyMutex.withLock {
            val uniqueAssignments = assignments
                .filter { it.hasValidTarget && it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .sortedBy { it.packageName }
            if (uniqueAssignments.isEmpty()) {
                return@withLock Result.failure(IllegalArgumentException("No visible app profiles"))
            }

            val state = observeState().first()
            if (!state.isPrivilegedHostAvailable || state.policies.isEmpty()) {
                return@withLock Result.failure(IllegalStateException("Profile automation is unavailable"))
            }
            ensureNormalBaselineLocked(state)
            val normalCpu = profileStorage.lastValues.first()
            val normalGpu = profileStorage.lastGpuValue.first()
            val targets = uniqueAssignments.map { assignment ->
                if (assignment.isCustom) {
                    val cpu = assignment.customMaxFrequencies.filterKeys { policyId ->
                        state.policies.any { it.id == policyId }
                    }
                    VisibleAppProfileTarget(
                        packageName = assignment.packageName,
                        appLabel = assignment.appLabel,
                        profileId = null,
                        profileName = "Custom",
                        cpuMaxFrequencies = cpu,
                        gpuMaxFrequencyHz = assignment.customGpuMaxFrequencyHz,
                    )
                } else {
                    val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId }
                        ?: return@withLock Result.failure(IllegalStateException("App profile is unavailable"))
                    VisibleAppProfileTarget(
                        packageName = assignment.packageName,
                        appLabel = assignment.appLabel,
                        profileId = profile.id,
                        profileName = profile.name,
                        cpuMaxFrequencies = profile.maxFrequencies,
                        gpuMaxFrequencyHz = resolveAppProfileGpuTarget(
                            isCustom = false,
                            explicitGpu = profile.gpuMaxFrequencyHz,
                            observedGpu = state.gpuPolicy?.observedMaxFrequencyHz,
                            normalBaselineGpu = normalGpu,
                        ),
                    )
                }
            }
            val combined = CombinedAppProfileResolver.resolve(targets, state.displayProfiles)
            val completeCpu = state.policies.associate { policy ->
                val value = combined.cpuMaxFrequencies[policy.id] ?: normalCpu[policy.id]
                    ?: return@withLock Result.failure(IllegalStateException("Normal CPU baseline is unavailable"))
                policy.id to value
            }
            val gpuTarget = state.gpuPolicy?.let {
                combined.gpuMaxFrequencyHz ?: normalGpu ?: it.observedMaxFrequencyHz
            }
            val isStock = state.policies.all { policy ->
                completeCpu[policy.id] == policy.observedMaxFreq
            } && (state.gpuPolicy == null || gpuTarget == state.gpuPolicy.observedMaxFrequencyHz)
            val presentation = combined.presentation
                ?: return@withLock Result.failure(IllegalStateException("Unable to resolve visible app profiles"))
            val isCombined = presentation.isCombined
            val profileId = presentation.id ?: if (isCombined) {
                CombinedAppProfileResolver.COMBINED_PROFILE_ID
            } else {
                "app:${combined.contributors.single().packageName}"
            }
            val result = applyValuesLocked(
                policies = state.policies,
                selectedValues = completeCpu,
                isReset = isStock,
                appliedDisplayProfileId = profileId,
                persistNormalState = false,
                allowObservedMaxValues = completeCpu.any { (policyId, value) ->
                    state.policies.firstOrNull { it.id == policyId }?.observedMaxFreq == value
                },
                gpuPolicy = state.gpuPolicy,
                selectedGpuMaxFrequencyHz = gpuTarget,
            )
            result.map { hardware ->
                AppProfileApplyOutcome(
                    hardware = hardware,
                    profileId = profileId,
                    profileName = presentation.name,
                    contributingPackages = combined.contributors.map { it.packageName },
                    isCombined = isCombined,
                )
            }
        }
    }

    suspend fun restoreNormalProfileTemporarily(): Result<ApplyOutcome> {
        return restoreNormalProfileTemporarilyWithIdentity().map { it.hardware }
    }

    suspend fun restoreNormalProfileTemporarilyWithIdentity(): Result<TemporaryRestoreOutcome> {
        return processApplyMutex.withLock {
            val state = observeState().first()
            if (!state.isPrivilegedHostAvailable || state.policies.isEmpty()) return@withLock Result.failure(IllegalStateException("Profile automation is unavailable"))
            ensureNormalBaselineLocked(state)
            val id = profileStorage.lastAppliedDisplayProfileId.first()
            val values = profileStorage.lastValues.first()
            val target = resolvePersistedTarget(state.policies, state.displayProfiles, id, values, profileStorage.lastGpuValue.first(), gpuStockFallback = state.gpuPolicy?.observedMaxFrequencyHz)
                ?: return@withLock Result.failure(IllegalStateException("No previous profile to restore"))
            val result = applyValuesLocked(
                state.policies,
                target.values,
                target.isReset,
                target.profileId,
                false,
                allowObservedMaxValues = allowsObservedMaxValues(target, state.policies),
                gpuPolicy = state.gpuPolicy,
                selectedGpuMaxFrequencyHz = target.gpuValue,
            )
            result.map { hardware ->
                val profileId = target.profileId ?: ProfileStateResolver.MANUAL_PROFILE_ID
                val profileName = when (profileId) {
                    ProfileStateResolver.STOCK_PROFILE_ID -> "Stock"
                    ProfileStateResolver.MANUAL_PROFILE_ID -> "Manual"
                    else -> state.displayProfiles.firstOrNull { it.id == profileId }?.name ?: "Previous profile"
                }
                TemporaryRestoreOutcome(hardware, profileId, profileName)
            }
        }
    }

    private suspend fun ensureNormalBaselineLocked(state: TunerState) {
        val values = profileStorage.lastValues.first()
        val storedGpu = profileStorage.lastGpuValue.first()
        val id = profileStorage.lastAppliedDisplayProfileId.first()
        val resolved = resolvePersistedTarget(
            policies = state.policies,
            profiles = state.displayProfiles,
            profileId = id,
            values = values,
            gpuValue = storedGpu,
            gpuStockFallback = state.gpuPolicy?.observedMaxFrequencyHz,
        )
        if (resolved != null) {
            val safeGpu = when {
                state.gpuPolicy == null -> null
                resolved.gpuValue?.let { isValidGpuValue(state.gpuPolicy, it) } == true -> resolved.gpuValue
                else -> state.gpuPolicy.observedMaxFrequencyHz
            }
            if (resolved.values != values || resolved.profileId != id || safeGpu != storedGpu) {
                profileStorage.persistNormalProfileState(resolved.values, resolved.profileId, safeGpu)
            }
            return
        }
        // Live values can be a temporary app-profile cap that survived an
        // update or process restart. Do not infer a named/manual baseline from
        // those values when persisted state is missing or invalid. Re-establish
        // a deterministic Stock target from observed hardware ceilings,
        // including bins hidden from the selectable-frequency list.
        val fallback = resolveLegacyStockBaseline(state.policies)
        profileStorage.persistNormalProfileState(fallback.values, fallback.profileId, state.gpuPolicy?.observedMaxFrequencyHz)
    }

    /**
     * Records a successful transition.  Persist the lightweight effective
     * identity before writing history so event-driven consumers (notably the
     * Quick Settings tile) always observe the new state first.
     *
     * Callers which represent a non-standard source (app/composite/sleep)
     * should pass it explicitly.  The default keeps legacy/manual call sites
     * deterministic while they migrate.
     */
    suspend fun logProfileSwitch(
        profileId: String?,
        profileName: String,
        trigger: String,
        effectiveSource: EffectiveProfileSource? = null,
        contributingPackageNames: List<String> = emptyList(),
    ) {
        val source = effectiveSource ?: when (profileId) {
            ProfileStateResolver.STOCK_PROFILE_ID -> EffectiveProfileSource.STOCK
            ProfileStateResolver.MANUAL_PROFILE_ID, null -> EffectiveProfileSource.MANUAL
            else -> EffectiveProfileSource.NORMAL
        }
        profileStorage.persistEffectiveProfileState(
            EffectiveProfileState(
                id = profileId ?: ProfileStateResolver.MANUAL_PROFILE_ID,
                name = profileName,
                source = source,
                contributingPackageNames = contributingPackageNames,
            ),
        )
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
                gpuMaxFrequencyHz = importedProfile.gpuMaxFrequencyHz,
                source = ProfileSource.BUNDLED,
                isEditable = true,
                isDeletable = true,
            )
        } else {
            val existing = currentById[importedProfile.id]
            existing?.copy(
                name = importedProfile.name,
                maxFrequencies = importedProfile.maxFrequencies,
                gpuMaxFrequencyHz = importedProfile.gpuMaxFrequencyHz,
                source = ProfileSource.USER,
                isEditable = true,
                isDeletable = true,
            )
                ?: importedProfile.copy(
                    source = ProfileSource.USER,
                    gpuMaxFrequencyHz = importedProfile.gpuMaxFrequencyHz,
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

private fun Long.toIntChecked(): Int {
    require(this in 1..Int.MAX_VALUE) { "Host value out of range: $this" }
    return toInt()
}

private fun isCompleteValidValues(values: Map<Int, Int>, policies: List<CpuPolicyInfo>, isReset: Boolean = false): Boolean =
    policies.isNotEmpty() && policies.all { policy ->
        val value = values[policy.id]
        value != null && (value in policy.supportedFrequencies || value == policy.observedMaxFreq)
    }

private fun isValidGpuValue(policy: GpuPolicyInfo, value: Int): Boolean =
    value > 0 && (value in policy.supportedFrequenciesHz || value == policy.observedMaxFrequencyHz)

internal fun resolveAppProfileGpuTarget(
    isCustom: Boolean,
    explicitGpu: Int?,
    observedGpu: Int?,
    normalBaselineGpu: Int?,
): Int? = if (isCustom) explicitGpu ?: normalBaselineGpu else explicitGpu ?: observedGpu

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
    policies: List<CpuPolicyInfo>, profiles: List<PerformanceProfile>, profileId: String?, values: Map<Int, Int>, gpuValue: Int? = null,
    gpuStockFallback: Int? = null,
): ResolvedPerformanceTarget? {
    val profile = profileId?.let { id -> profiles.firstOrNull { it.id == id } }
    fun normalized(source: Map<Int, Int>): Map<Int, Int> = policies.associate { it.id to source.getValue(it.id) }
    if (profile != null) {
        val reset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID
        return profile.maxFrequencies.takeIf { isCompleteValidValues(it, policies, reset) }
            ?.let {
                ResolvedPerformanceTarget(
                    values = normalized(it),
                    profileId = profile.id,
                    isReset = reset,
                    // A null GPU field is unspecified for ordinary profiles;
                    // Stock may use the observed fallback when its ceiling is
                    // hidden from the selectable-frequency list.
                    gpuValue = profile.gpuMaxFrequencyHz
                        ?: if (reset) gpuStockFallback ?: gpuValue else gpuValue,
                )
            }
    }
    if (profileId != null && profileId != ProfileStateResolver.MANUAL_PROFILE_ID) {
        if (!isCompleteValidValues(values, policies, true)) return null
        return ResolvedPerformanceTarget(normalized(values), ProfileStateResolver.MANUAL_PROFILE_ID, false, gpuValue)
    }
    return values.takeIf { isCompleteValidValues(it, policies, true) }
        ?.let { ResolvedPerformanceTarget(normalized(it), ProfileStateResolver.MANUAL_PROFILE_ID, false, gpuValue) }
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
