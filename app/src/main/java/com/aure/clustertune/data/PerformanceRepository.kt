package com.aure.clustertune.data

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
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
    val selectedProfileId: String?,
    val lastAppliedDisplayProfileId: String?,
)

private data class PartialStorageState(
    val storedProfiles: List<PerformanceProfile>,
    val deletedBundledProfileIds: Set<String>,
    val displayOrder: List<String>,
    val lastValues: Map<Int, Int>,
)

class PerformanceRepository(
    private val detector: CpuPolicyDetector,
    private val bundledProfileProvider: BundledProfileProvider,
    private val profileStorage: ProfileStorage,
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
        val storageState = combine(
            profileStorage.profiles,
            profileStorage.deletedBundledProfileIds,
            profileStorage.displayOrder,
            profileStorage.lastValues,
        ) { storedProfiles, deletedBundledProfileIds, displayOrder, lastValues ->
            PartialStorageState(
                storedProfiles = storedProfiles,
                deletedBundledProfileIds = deletedBundledProfileIds,
                displayOrder = displayOrder,
                lastValues = lastValues,
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
        val filtered = selectedValues.filterKeys { policyId -> policies.any { it.id == policyId } }
        val script = commandBuilder.buildApplyScript(policies, filtered, isReset)
        return rootCommandRunner.executeScript(script).mapCatching { output ->
            profileStorage.persistLastValues(filtered)
            profileStorage.persistLastAppliedDisplayProfile(appliedDisplayProfileId)
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
        val bundledProfilesById = currentProfiles
            .filter { it.source == ProfileSource.BUNDLED }
            .associateBy { it.id }
        val validProfiles = ProfileJsonCodec.parseShareProfiles(rawJson)
            .filter { profile ->
                profile.maxFrequencies.isNotEmpty() &&
                    profile.maxFrequencies.all { (policyId, frequency) ->
                        val policy = policyIds[policyId] ?: return@all false
                        frequency in policy.supportedFrequencies
                    }
            }

        var createdUserProfiles = 0
        validProfiles.forEach { importedProfile ->
            val bundledProfile = bundledProfilesById[importedProfile.id]
            if (bundledProfile != null) {
                profileStorage.unmarkBundledProfileDeleted(bundledProfile.id)
                profileStorage.saveProfile(
                    bundledProfile.copy(
                        name = importedProfile.name,
                        maxFrequencies = importedProfile.maxFrequencies,
                        isEditable = true,
                        isDeletable = true,
                    ),
                )
            } else {
                profileStorage.saveProfile(
                    importedProfile.copy(
                        id = "user_${UUID.randomUUID()}",
                        source = ProfileSource.USER,
                        order = currentProfiles.size + createdUserProfiles,
                        isEditable = true,
                        isDeletable = true,
                    ),
                )
                createdUserProfiles += 1
            }
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
