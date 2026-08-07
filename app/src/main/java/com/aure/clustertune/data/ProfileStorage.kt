package com.aure.clustertune.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.ProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "android_tuner")

class ProfileStorage(private val context: Context) {

    private val profilesKey = stringPreferencesKey("user_profiles")
    private val lastValuesKey = stringPreferencesKey("last_values")
    private val lastGpuValueKey = stringPreferencesKey("last_gpu_value")
    private val selectedProfileKey = stringPreferencesKey("selected_profile")
    private val lastAppliedDisplayProfileKey = stringPreferencesKey("last_applied_display_profile")
    private val sleepRestoreValuesKey = stringPreferencesKey("sleep_restore_values")
    private val sleepRestoreGpuValueKey = stringPreferencesKey("sleep_restore_gpu_value")
    private val sleepRestoreGpuKnownKey = booleanPreferencesKey("sleep_restore_gpu_known")
    private val sleepRestoreDisplayProfileKey = stringPreferencesKey("sleep_restore_display_profile")
    private val deletedBundledProfileIdsKey = stringSetPreferencesKey("deleted_bundled_profile_ids")
    private val displayOrderKey = stringPreferencesKey("display_order")
    private val appProfileAssignmentsKey = stringPreferencesKey("app_profile_assignments")
    private val profileSwitchHistoryKey = stringPreferencesKey("profile_switch_history")

    val profiles: Flow<List<PerformanceProfile>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseProfiles(preferences[profilesKey])
    }.distinctUntilChanged()

    val deletedBundledProfileIds: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[deletedBundledProfileIdsKey] ?: emptySet()
    }.distinctUntilChanged()

    val displayOrder: Flow<List<String>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseStringList(preferences[displayOrderKey])
    }.distinctUntilChanged()

    val appProfileAssignments: Flow<List<AppProfileAssignment>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseAppProfileAssignments(preferences[appProfileAssignmentsKey])
    }.distinctUntilChanged()

    val profileSwitchHistory: Flow<List<ProfileSwitchHistoryEntry>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseProfileSwitchHistory(preferences[profileSwitchHistoryKey])
    }.distinctUntilChanged()

    val lastValues: Flow<Map<Int, Int>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseIntMap(preferences[lastValuesKey])
    }.distinctUntilChanged()

    val selectedProfileId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[selectedProfileKey]
    }.distinctUntilChanged()

    val lastAppliedDisplayProfileId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[lastAppliedDisplayProfileKey]
    }.distinctUntilChanged()

    val sleepRestoreValues: Flow<Map<Int, Int>> = context.dataStore.data.map { preferences ->
        ProfileStorageCodec.parseIntMap(preferences[sleepRestoreValuesKey])
    }.distinctUntilChanged()
    val lastGpuValue: Flow<Int?> = context.dataStore.data.map { it[lastGpuValueKey] }
        .map { it?.toIntOrNull()?.takeIf { value -> value > 0 } }
        .distinctUntilChanged()
    val sleepRestoreGpuValue: Flow<Int?> = context.dataStore.data.map { it[sleepRestoreGpuValueKey] }
        .map { it?.toIntOrNull()?.takeIf { value -> value > 0 } }
        .distinctUntilChanged()
    val sleepRestoreGpuKnown: Flow<Boolean> = context.dataStore.data.map { it[sleepRestoreGpuKnownKey] ?: false }
        .distinctUntilChanged()

    val sleepRestoreDisplayProfileId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[sleepRestoreDisplayProfileKey]
    }.distinctUntilChanged()

    suspend fun saveProfile(profile: PerformanceProfile) {
        context.dataStore.edit { preferences ->
            val current = ProfileStorageCodec.parseProfiles(preferences[profilesKey]).toMutableList()
            current.removeAll { it.id == profile.id }
            current.add(profile)
            preferences[profilesKey] = ProfileStorageCodec.encodeProfiles(normalizeOrders(current))
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.dataStore.edit { preferences ->
            val current = ProfileStorageCodec.parseProfiles(preferences[profilesKey]).filterNot { it.id == profileId }
            preferences[profilesKey] = ProfileStorageCodec.encodeProfiles(normalizeOrders(current))
        }
    }

    suspend fun replaceProfiles(profiles: List<PerformanceProfile>) {
        context.dataStore.edit { preferences ->
            preferences[profilesKey] = ProfileStorageCodec.encodeProfiles(
                profiles.mapIndexed { index, profile ->
                    profile.copy(
                        order = index,
                        isEditable = true,
                        isDeletable = profile.source != ProfileSource.VIRTUAL,
                    )
                },
            )
        }
    }

    suspend fun persistDisplayOrder(profileIds: List<String>) {
        context.dataStore.edit { preferences ->
            preferences[displayOrderKey] = ProfileStorageCodec.encodeStringList(profileIds)
        }
    }

    suspend fun saveAppProfileAssignment(assignment: AppProfileAssignment) {
        context.dataStore.edit { preferences ->
            val current = ProfileStorageCodec
                .parseAppProfileAssignments(preferences[appProfileAssignmentsKey])
                .filterNot { it.packageName == assignment.packageName }
            preferences[appProfileAssignmentsKey] = ProfileStorageCodec.encodeAppProfileAssignments(
                current + assignment,
            )
        }
    }

    suspend fun deleteAppProfileAssignment(packageName: String) {
        context.dataStore.edit { preferences ->
            val updated = ProfileStorageCodec
                .parseAppProfileAssignments(preferences[appProfileAssignmentsKey])
                .filterNot { it.packageName == packageName }
            if (updated.isEmpty()) {
                preferences.remove(appProfileAssignmentsKey)
            } else {
                preferences[appProfileAssignmentsKey] = ProfileStorageCodec.encodeAppProfileAssignments(updated)
            }
        }
    }

    suspend fun appendProfileSwitchHistory(entry: ProfileSwitchHistoryEntry, limit: Int) {
        context.dataStore.edit { preferences ->
            val current = ProfileStorageCodec.parseProfileSwitchHistory(preferences[profileSwitchHistoryKey])
            preferences[profileSwitchHistoryKey] = ProfileStorageCodec.encodeProfileSwitchHistory(
                boundedProfileSwitchHistory(entry, current, limit),
            )
        }
    }

    suspend fun trimProfileSwitchHistory(limit: Int) {
        context.dataStore.edit { preferences ->
            val current = ProfileStorageCodec.parseProfileSwitchHistory(preferences[profileSwitchHistoryKey])
            if (current.size <= limit) return@edit
            preferences[profileSwitchHistoryKey] = ProfileStorageCodec.encodeProfileSwitchHistory(
                current.take(limit),
            )
        }
    }

    suspend fun clearProfileSwitchHistory() {
        context.dataStore.edit { preferences ->
            preferences.remove(profileSwitchHistoryKey)
        }
    }

    suspend fun resetProfiles() {
        context.dataStore.edit { preferences ->
            preferences.remove(profilesKey)
            preferences.remove(deletedBundledProfileIdsKey)
            preferences.remove(displayOrderKey)
            preferences.remove(appProfileAssignmentsKey)
        }
    }

    suspend fun markBundledProfileDeleted(profileId: String) {
        context.dataStore.edit { preferences ->
            val deleted = (preferences[deletedBundledProfileIdsKey] ?: emptySet()).toMutableSet()
            deleted.add(profileId)
            preferences[deletedBundledProfileIdsKey] = deleted
        }
    }

    suspend fun unmarkBundledProfileDeleted(profileId: String) {
        context.dataStore.edit { preferences ->
            val deleted = (preferences[deletedBundledProfileIdsKey] ?: emptySet()).toMutableSet()
            deleted.remove(profileId)
            if (deleted.isEmpty()) {
                preferences.remove(deletedBundledProfileIdsKey)
            } else {
                preferences[deletedBundledProfileIdsKey] = deleted
            }
        }
    }

    suspend fun persistLastValues(values: Map<Int, Int>) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = ProfileStorageCodec.encodeIntMap(values)
        }
    }

    suspend fun persistLastValues(values: Map<Int, Int>, gpuValue: Int?) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (gpuValue == null) preferences.remove(lastGpuValueKey) else preferences[lastGpuValueKey] = gpuValue.toString()
        }
    }

    suspend fun persistSelectedProfile(profileId: String?) {
        context.dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(selectedProfileKey)
            } else {
                preferences[selectedProfileKey] = profileId
            }
        }
    }

    suspend fun persistLastAppliedDisplayProfile(profileId: String?) {
        context.dataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(lastAppliedDisplayProfileKey)
            } else {
                preferences[lastAppliedDisplayProfileKey] = profileId
            }
        }
    }

    suspend fun persistNormalProfileState(values: Map<Int, Int>, profileId: String?) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (profileId == null) {
                preferences.remove(lastAppliedDisplayProfileKey)
            } else {
                preferences[lastAppliedDisplayProfileKey] = profileId
            }
        }
    }

    suspend fun persistNormalProfileState(values: Map<Int, Int>, profileId: String?, gpuValue: Int?) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (gpuValue == null) preferences.remove(lastGpuValueKey) else preferences[lastGpuValueKey] = gpuValue.toString()
            if (profileId == null) preferences.remove(lastAppliedDisplayProfileKey) else preferences[lastAppliedDisplayProfileKey] = profileId
        }
    }

    /** Persists normal-state values and selection projections in one DataStore transaction. */
    suspend fun persistNormalProfileState(
        values: Map<Int, Int>,
        lastAppliedProfileId: String?,
        gpuValue: Int?,
        selectedProfileId: String?,
    ) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (gpuValue == null) preferences.remove(lastGpuValueKey) else preferences[lastGpuValueKey] = gpuValue.toString()
            if (lastAppliedProfileId == null) preferences.remove(lastAppliedDisplayProfileKey)
            else preferences[lastAppliedDisplayProfileKey] = lastAppliedProfileId
            if (selectedProfileId == null) preferences.remove(selectedProfileKey)
            else preferences[selectedProfileKey] = selectedProfileId
        }
    }

    suspend fun persistSleepRestoreState(values: Map<Int, Int>, profileId: String?) {
        context.dataStore.edit { preferences ->
            preferences[sleepRestoreValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (profileId == null) {
                preferences.remove(sleepRestoreDisplayProfileKey)
            } else {
                preferences[sleepRestoreDisplayProfileKey] = profileId
            }
        }
    }

    suspend fun persistSleepRestoreState(values: Map<Int, Int>, profileId: String?, gpuValue: Int?, gpuKnown: Boolean = gpuValue != null) {
        context.dataStore.edit { preferences ->
            preferences[sleepRestoreValuesKey] = ProfileStorageCodec.encodeIntMap(values)
            if (gpuValue == null) preferences.remove(sleepRestoreGpuValueKey) else preferences[sleepRestoreGpuValueKey] = gpuValue.toString()
            preferences[sleepRestoreGpuKnownKey] = gpuKnown
            if (profileId == null) preferences.remove(sleepRestoreDisplayProfileKey) else preferences[sleepRestoreDisplayProfileKey] = profileId
        }
    }

    suspend fun clearSleepRestoreState() {
        context.dataStore.edit { preferences ->
            preferences.remove(sleepRestoreValuesKey)
            preferences.remove(sleepRestoreDisplayProfileKey)
            preferences.remove(sleepRestoreGpuValueKey)
            preferences.remove(sleepRestoreGpuKnownKey)
        }
    }

    private fun normalizeOrders(profiles: List<PerformanceProfile>): List<PerformanceProfile> {
        return profiles
            .sortedBy { it.order }
            .mapIndexed { index, profile ->
                profile.copy(
                    order = index,
                    isEditable = true,
                    isDeletable = profile.source != ProfileSource.VIRTUAL,
                )
            }
    }
}

internal fun boundedProfileSwitchHistory(
    entry: ProfileSwitchHistoryEntry,
    current: List<ProfileSwitchHistoryEntry>,
    limit: Int,
): List<ProfileSwitchHistoryEntry> {
    return (listOf(entry) + current).take(limit)
}
