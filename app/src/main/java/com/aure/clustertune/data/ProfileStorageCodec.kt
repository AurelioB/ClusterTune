package com.aure.clustertune.data

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.ProfileSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProfileStorageCodec {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeProfiles(profiles: List<PerformanceProfile>): String {
        return json.encodeToString<List<StoredProfile>>(
            profiles.map { profile ->
                StoredProfile(
                    id = profile.id,
                    name = profile.name,
                    source = profile.source.name,
                    order = profile.order,
                    isEditable = profile.isEditable,
                    isDeletable = profile.isDeletable,
                    maxFrequencies = profile.maxFrequencies.mapKeys { (policyId, _) -> policyId.toString() },
                    gpuMaxFrequencyHz = profile.gpuMaxFrequencyHz?.takeIf { it > 0 },
                )
            },
        )
    }

    fun parseProfiles(raw: String?): List<PerformanceProfile> {
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            json.decodeFromString<List<StoredProfile>>(raw).map { profile ->
                PerformanceProfile(
                    id = profile.id,
                    name = profile.name,
                    maxFrequencies = profile.maxFrequencies.mapNotNull { (policyId, frequency) ->
                        policyId.toIntOrNull()?.takeIf { frequency > 0 }?.let { it to frequency }
                    }.toMap(),
                    source = parseSource(profile.source),
                    order = profile.order,
                    isEditable = profile.isEditable,
                    isDeletable = profile.isDeletable,
                    gpuMaxFrequencyHz = profile.gpuMaxFrequencyHz?.takeIf { it > 0 },
                )
            }
        }.getOrDefault(emptyList()).sortedBy { it.order }
    }

    fun encodeIntMap(values: Map<Int, Int>): String {
        return json.encodeToString<Map<String, Int>>(
            values.toSortedMap().mapKeys { (policyId, _) -> policyId.toString() },
        )
    }

    fun parseIntMap(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()

        return runCatching {
            json.decodeFromString<Map<String, Int>>(raw).mapNotNull { (policyId, frequency) ->
                policyId.toIntOrNull()?.takeIf { frequency > 0 }?.let { it to frequency }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun encodeStringList(values: List<String>): String {
        return json.encodeToString<List<String>>(values)
    }

    fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    fun encodeAppProfileAssignments(assignments: List<AppProfileAssignment>): String {
        return json.encodeToString<List<StoredAppProfileAssignment>>(
            assignments.sortedBy { it.appLabel.lowercase() }.map { assignment ->
                StoredAppProfileAssignment(
                    packageName = assignment.packageName,
                    appLabel = assignment.appLabel,
                    profileId = assignment.profileId,
                    customMaxFrequencies = assignment.customMaxFrequencies
                        .mapKeys { (policyId, _) -> policyId.toString() },
                    customGpuMaxFrequencyHz = assignment.customGpuMaxFrequencyHz,
                )
            },
        )
    }

    fun parseAppProfileAssignments(raw: String?): List<AppProfileAssignment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredAppProfileAssignment>>(raw)
                .mapNotNull { assignment ->
                    val packageName = assignment.packageName.trim()
                    val profileId = assignment.profileId?.trim()?.takeIf { it.isNotBlank() }
                    val parsedCustomValues = assignment.customMaxFrequencies.mapNotNull { (policyId, frequency) ->
                        policyId.toIntOrNull()?.takeIf { frequency > 0 }?.let { it to frequency }
                    }.toMap()
                    val customValues = if (profileId == null) parsedCustomValues else emptyMap()
                    // Older builds could persist the profile's GPU value next
                    // to its profileId. Treat that as redundant metadata and
                    // keep the named assignment rather than dropping it.
                    val customGpu = if (profileId == null) assignment.customGpuMaxFrequencyHz?.takeIf { it > 0 } else null
                    if (packageName.isBlank() || (profileId == null && customValues.isEmpty() && customGpu == null)) return@mapNotNull null
                    AppProfileAssignment(
                        packageName = packageName,
                        appLabel = assignment.appLabel.ifBlank { packageName },
                        profileId = profileId,
                        customMaxFrequencies = customValues,
                        customGpuMaxFrequencyHz = customGpu,
                    )
                }
                .sortedBy { it.appLabel.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun encodeProfileSwitchHistory(entries: List<ProfileSwitchHistoryEntry>): String {
        return json.encodeToString<List<StoredProfileSwitchHistoryEntry>>(
            entries.map { entry ->
                StoredProfileSwitchHistoryEntry(
                    timestampMillis = entry.timestampMillis,
                    profileId = entry.profileId,
                    profileName = entry.profileName,
                    trigger = entry.trigger,
                )
            },
        )
    }

    fun parseProfileSwitchHistory(raw: String?): List<ProfileSwitchHistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<StoredProfileSwitchHistoryEntry>>(raw)
                .mapNotNull { entry ->
                    val profileName = entry.profileName.trim()
                    val trigger = entry.trigger.trim()
                    if (profileName.isBlank() || trigger.isBlank()) return@mapNotNull null
                    ProfileSwitchHistoryEntry(
                        timestampMillis = entry.timestampMillis,
                        profileId = entry.profileId,
                        profileName = profileName,
                        trigger = trigger,
                    )
                }
                .sortedByDescending { it.timestampMillis }
        }.getOrDefault(emptyList())
    }

    private fun parseSource(raw: String): ProfileSource {
        return runCatching { ProfileSource.valueOf(raw) }.getOrDefault(ProfileSource.USER)
    }

    @Serializable
    private data class StoredProfile(
        val id: String,
        val name: String,
        val source: String = ProfileSource.USER.name,
        val order: Int = 0,
        val isEditable: Boolean = true,
        val isDeletable: Boolean = true,
        @SerialName("maxFrequencies")
        val maxFrequencies: Map<String, Int> = emptyMap(),
        val gpuMaxFrequencyHz: Int? = null,
    )

    @Serializable
    private data class StoredAppProfileAssignment(
        val packageName: String,
        val appLabel: String,
        val profileId: String? = null,
        val customMaxFrequencies: Map<String, Int> = emptyMap(),
        val customGpuMaxFrequencyHz: Int? = null,
    )

    @Serializable
    private data class StoredProfileSwitchHistoryEntry(
        val timestampMillis: Long,
        val profileId: String? = null,
        val profileName: String,
        val trigger: String,
    )
}
