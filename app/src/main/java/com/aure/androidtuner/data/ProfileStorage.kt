package com.aure.androidtuner.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aure.androidtuner.model.PerformanceProfile
import com.aure.androidtuner.model.ProfileSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "android_tuner")

class ProfileStorage(private val context: Context) {

    private val userProfilesKey = stringPreferencesKey("user_profiles")
    private val lastValuesKey = stringPreferencesKey("last_values")
    private val selectedProfileKey = stringPreferencesKey("selected_profile")

    val userProfiles: Flow<List<PerformanceProfile>> = context.dataStore.data.map { preferences ->
        parseProfiles(preferences[userProfilesKey])
    }

    val lastValues: Flow<Map<Int, Int>> = context.dataStore.data.map { preferences ->
        parseIntMap(preferences[lastValuesKey])
    }

    val selectedProfileId: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[selectedProfileKey]
    }

    suspend fun saveUserProfile(profile: PerformanceProfile) {
        context.dataStore.edit { preferences ->
            val current = parseProfiles(preferences[userProfilesKey]).toMutableList()
            current.removeAll { it.id == profile.id }
            current.add(profile.copy(source = ProfileSource.USER))
            preferences[userProfilesKey] = encodeProfiles(current)
        }
    }

    suspend fun persistLastValues(values: Map<Int, Int>) {
        context.dataStore.edit { preferences ->
            preferences[lastValuesKey] = encodeIntMap(values)
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

    private fun encodeProfiles(profiles: List<PerformanceProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("source", profile.source.name)
                    .put("isResetProfile", profile.isResetProfile)
                    .put("maxFrequencies", JSONObject(encodeIntMap(profile.maxFrequencies))),
            )
        }
        return array.toString()
    }

    private fun parseProfiles(raw: String?): List<PerformanceProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        PerformanceProfile(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            maxFrequencies = parseIntMap(item.get("maxFrequencies").toString()),
                            source = ProfileSource.valueOf(item.optString("source", ProfileSource.USER.name)),
                            isResetProfile = item.optBoolean("isResetProfile", false),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeIntMap(values: Map<Int, Int>): String {
        val json = JSONObject()
        values.toSortedMap().forEach { (key, value) -> json.put(key.toString(), value) }
        return json.toString()
    }

    private fun parseIntMap(raw: String?): Map<Int, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val intKey = key.toIntOrNull() ?: continue
                    val value = json.optInt(key)
                    if (value > 0) {
                        put(intKey, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }
}
