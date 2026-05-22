package com.aure.clustertune.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.TileInteractionBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "android_tuner_settings")

class SettingsStorage(private val context: Context) {

    private val tileTapBehaviorKey = stringPreferencesKey("tile_tap_behavior")
    private val applyLastProfileOnBootKey = booleanPreferencesKey("apply_last_profile_on_boot")
    private val sleepProfileEnabledKey = booleanPreferencesKey("sleep_profile_enabled")
    private val sleepProfileIdKey = stringPreferencesKey("sleep_profile_id")
    private val quickSettingsTilePromptShownKey = booleanPreferencesKey("quick_settings_tile_prompt_shown")
    private val quickSettingsTileAddedKey = booleanPreferencesKey("quick_settings_tile_added")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val accentColorKey = intPreferencesKey("accent_color")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            colorSource = preferences[colorSourceKey]
                ?.let(::parseColorSource)
                ?: AppColorSource.SYSTEM,
            accentColor = preferences[accentColorKey] ?: 0xFF3F51B5.toInt(),
            tileTapBehavior = preferences[tileTapBehaviorKey]
                ?.let(::parseBehavior)
                ?: TileInteractionBehavior.SHOW_DIALOG,
            applyLastProfileOnBoot = preferences[applyLastProfileOnBootKey] ?: false,
            sleepProfileEnabled = preferences[sleepProfileEnabledKey] ?: false,
            sleepProfileId = preferences[sleepProfileIdKey],
            hasPromptedQuickSettingsTile = preferences[quickSettingsTilePromptShownKey] ?: false,
            isQuickSettingsTileAdded = preferences[quickSettingsTileAddedKey] ?: false,
        )
    }

    suspend fun persistTileTapBehavior(behavior: TileInteractionBehavior) {
        context.settingsDataStore.edit { preferences ->
            preferences[tileTapBehaviorKey] = behavior.name
        }
    }

    suspend fun persistApplyLastProfileOnBoot(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[applyLastProfileOnBootKey] = enabled
        }
    }

    suspend fun persistSleepProfileEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[sleepProfileEnabledKey] = enabled
        }
    }

    suspend fun persistSleepProfileId(profileId: String?) {
        context.settingsDataStore.edit { preferences ->
            if (profileId == null) {
                preferences.remove(sleepProfileIdKey)
            } else {
                preferences[sleepProfileIdKey] = profileId
            }
        }
    }

    suspend fun persistSleepProfile(enabled: Boolean, profileId: String?) {
        context.settingsDataStore.edit { preferences ->
            preferences[sleepProfileEnabledKey] = enabled
            if (profileId == null) {
                preferences.remove(sleepProfileIdKey)
            } else {
                preferences[sleepProfileIdKey] = profileId
            }
        }
    }

    suspend fun persistQuickSettingsTilePromptShown() {
        context.settingsDataStore.edit { preferences ->
            preferences[quickSettingsTilePromptShownKey] = true
        }
    }

    suspend fun persistQuickSettingsTileAdded(isAdded: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[quickSettingsTileAddedKey] = isAdded
        }
    }

    suspend fun persistColorSource(colorSource: AppColorSource) {
        context.settingsDataStore.edit { preferences ->
            preferences[colorSourceKey] = colorSource.name
        }
    }

    suspend fun persistAccentColor(accentColor: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[accentColorKey] = accentColor
        }
    }

    private fun parseBehavior(raw: String): TileInteractionBehavior {
        return runCatching { TileInteractionBehavior.valueOf(raw) }
            .getOrDefault(TileInteractionBehavior.SHOW_DIALOG)
    }

    private fun parseColorSource(raw: String): AppColorSource {
        return runCatching { AppColorSource.valueOf(raw) }
            .getOrDefault(AppColorSource.SYSTEM)
    }
}
