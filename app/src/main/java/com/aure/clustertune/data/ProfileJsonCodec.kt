package com.aure.clustertune.data

import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProfileJsonCodec {

    private val shareJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeShareFile(profiles: List<PerformanceProfile>, socModel: String?): String {
        return shareJson.encodeToString(
            ProfileShareFile(
                socModel = socModel,
                profiles = profiles.map { profile ->
                    ProfileShareProfile(
                        id = profile.id,
                        name = profile.name,
                        maxFrequencies = profile.maxFrequencies.mapKeys { (policyId, _) -> policyId.toString() },
                        gpuMaxFrequencyHz = profile.gpuMaxFrequencyHz,
                    )
                },
            ),
        )
    }

    fun parseShareProfiles(raw: String?): List<PerformanceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            shareJson.decodeFromString<ProfileShareFile>(raw).profiles.mapIndexed { index, profile ->
                PerformanceProfile(
                    id = profile.id,
                    name = profile.name,
                    maxFrequencies = profile.maxFrequencies.mapNotNull { (policyId, frequency) ->
                        policyId.toIntOrNull()?.takeIf { frequency > 0 }?.let { it to frequency }
                    }.toMap(),
                    source = ProfileSource.USER,
                    order = index,
                    isEditable = true,
                    isDeletable = true,
                    gpuMaxFrequencyHz = profile.gpuMaxFrequencyHz?.takeIf { it > 0 },
                )
            }
        }.getOrDefault(emptyList())
    }

    @Serializable
    private data class ProfileShareFile(
        val schemaVersion: Int = 2,
        val socModel: String? = null,
        val profiles: List<ProfileShareProfile> = emptyList(),
    )

    @Serializable
    private data class ProfileShareProfile(
        val id: String,
        val name: String,
        @SerialName("maxFrequencies")
        val maxFrequencies: Map<String, Int>,
        val gpuMaxFrequencyHz: Int? = null,
    )
}
