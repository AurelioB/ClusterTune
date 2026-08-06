package com.aure.clustertune.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.DEFAULT_EDGE_HANDLE_HEIGHT_DP
import com.aure.clustertune.model.DEFAULT_EDGE_HANDLE_OPACITY_PERCENT
import com.aure.clustertune.model.DEFAULT_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.DEFAULT_EDGE_HANDLE_VERTICAL_POSITION_PERCENT
import com.aure.clustertune.model.DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MAX_EDGE_HANDLE_HEIGHT_DP
import com.aure.clustertune.model.MAX_EDGE_HANDLE_OPACITY_PERCENT
import com.aure.clustertune.model.MAX_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MIN_EDGE_HANDLE_HEIGHT_DP
import com.aure.clustertune.model.MIN_EDGE_HANDLE_OPACITY_PERCENT
import com.aure.clustertune.model.MIN_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT
import com.aure.clustertune.model.MIN_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.TileInteractionBehavior
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "android_tuner_settings")

private val bundledAccentColors = setOf(
    0xFF3F51B5.toInt(),
    0xFF006E1C.toInt(),
    0xFFB3261E.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00639A.toInt(),
    0xFF9A4600.toInt(),
)

class SettingsStorage(private val context: Context) {

    private val tileTapBehaviorKey = stringPreferencesKey("tile_tap_behavior")
    private val applyLastProfileOnBootKey = booleanPreferencesKey("apply_last_profile_on_boot")
    private val sleepProfileEnabledKey = booleanPreferencesKey("sleep_profile_enabled")
    private val sleepProfileIdKey = stringPreferencesKey("sleep_profile_id")
    private val quickSettingsTilePromptShownKey = booleanPreferencesKey("quick_settings_tile_prompt_shown")
    private val quickSettingsTileAddedKey = booleanPreferencesKey("quick_settings_tile_added")
    private val colorSourceKey = stringPreferencesKey("color_source")
    private val accentColorKey = intPreferencesKey("accent_color")
    private val customAccentColorKey = intPreferencesKey("custom_accent_color")
    private val automaticUpdateChecksEnabledKey = booleanPreferencesKey("automatic_update_checks_enabled")
    private val updateCheckIntervalDaysKey = intPreferencesKey("update_check_interval_days")
    private val includePrereleaseUpdatesKey = booleanPreferencesKey("include_prerelease_updates")
    private val lastUpdateCheckMillisKey = longPreferencesKey("last_update_check_millis")
    private val displayFrequenciesAsPercentKey = booleanPreferencesKey("display_frequencies_as_percent")
    private val leftEdgeProfilePickerEnabledKey = booleanPreferencesKey("left_edge_profile_picker_enabled")
    private val edgeHandleHeightDpKey = intPreferencesKey("edge_handle_height_dp")
    private val edgeHandleThicknessDpKey = intPreferencesKey("edge_handle_thickness_dp")
    private val edgeHandleVerticalPositionPercentKey =
        intPreferencesKey("edge_handle_vertical_position_percent")
    private val edgeHandleOpacityPercentKey = intPreferencesKey("edge_handle_opacity_percent")
    private val profileSwitchToastsEnabledKey = booleanPreferencesKey("profile_switch_toasts_enabled")
    private val profileSwitchHistoryLimitKey = intPreferencesKey("profile_switch_history_limit")
    private val privilegedExecutionMethodIdKey = stringPreferencesKey("privileged_execution_method_id")

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { preferences ->
        AppSettings(
            colorSource = preferences[colorSourceKey]
                ?.let(::parseColorSource)
                ?: AppColorSource.SYSTEM,
            accentColor = preferences[accentColorKey] ?: 0xFF3F51B5.toInt(),
            customAccentColor = preferences[customAccentColorKey]
                ?: preferences[accentColorKey]?.takeIf { accentColor -> accentColor !in bundledAccentColors }
                ?: 0xFF3F51B5.toInt(),
            tileTapBehavior = preferences[tileTapBehaviorKey]
                ?.let(::parseBehavior)
                ?: TileInteractionBehavior.SHOW_DIALOG,
            applyLastProfileOnBoot = preferences[applyLastProfileOnBootKey] ?: false,
            sleepProfileEnabled = preferences[sleepProfileEnabledKey] ?: false,
            sleepProfileId = preferences[sleepProfileIdKey],
            hasPromptedQuickSettingsTile = preferences[quickSettingsTilePromptShownKey] ?: false,
            isQuickSettingsTileAdded = preferences[quickSettingsTileAddedKey] ?: false,
            automaticUpdateChecksEnabled = preferences[automaticUpdateChecksEnabledKey] ?: true,
            updateCheckIntervalDays = (preferences[updateCheckIntervalDaysKey] ?: 7).coerceIn(1, 365),
            includePrereleaseUpdates = preferences[includePrereleaseUpdatesKey] ?: false,
            lastUpdateCheckMillis = preferences[lastUpdateCheckMillisKey] ?: 0L,
            displayFrequenciesAsPercent = preferences[displayFrequenciesAsPercentKey] ?: false,
            leftEdgeProfilePickerEnabled = preferences[leftEdgeProfilePickerEnabledKey] ?: false,
            edgeHandleHeightDp = (preferences[edgeHandleHeightDpKey] ?: DEFAULT_EDGE_HANDLE_HEIGHT_DP)
                .coerceIn(MIN_EDGE_HANDLE_HEIGHT_DP, MAX_EDGE_HANDLE_HEIGHT_DP),
            edgeHandleThicknessDp = normalizeEdgeHandleThicknessDp(
                preferences[edgeHandleThicknessDpKey] ?: DEFAULT_EDGE_HANDLE_THICKNESS_DP,
            ),
            edgeHandleVerticalPositionPercent = (preferences[edgeHandleVerticalPositionPercentKey]
                ?: DEFAULT_EDGE_HANDLE_VERTICAL_POSITION_PERCENT).coerceIn(
                MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
                MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
            ),
            edgeHandleOpacityPercent = normalizeEdgeHandleOpacityPercent(
                preferences[edgeHandleOpacityPercentKey] ?: DEFAULT_EDGE_HANDLE_OPACITY_PERCENT,
            ),
            profileSwitchToastsEnabled = preferences[profileSwitchToastsEnabledKey] ?: true,
            profileSwitchHistoryLimit = (preferences[profileSwitchHistoryLimitKey]
                ?: DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT)
                .coerceIn(MIN_PROFILE_SWITCH_HISTORY_LIMIT, MAX_PROFILE_SWITCH_HISTORY_LIMIT),
            privilegedExecutionMethodId = supportedExecutionMethodId(
                preferences[privilegedExecutionMethodIdKey],
            ),
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

    suspend fun persistPresetAccentColor(accentColor: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[colorSourceKey] = AppColorSource.CUSTOM_ACCENT.name
            preferences[accentColorKey] = accentColor
        }
    }

    suspend fun persistCustomAccentColor(accentColor: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[colorSourceKey] = AppColorSource.CUSTOM_ACCENT.name
            preferences[accentColorKey] = accentColor
            preferences[customAccentColorKey] = accentColor
        }
    }

    suspend fun persistAutomaticUpdateChecksEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[automaticUpdateChecksEnabledKey] = enabled
        }
    }

    suspend fun persistUpdateCheckIntervalDays(days: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[updateCheckIntervalDaysKey] = days.coerceIn(1, 365)
        }
    }

    suspend fun persistIncludePrereleaseUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[includePrereleaseUpdatesKey] = enabled
        }
    }

    suspend fun persistLastUpdateCheckMillis(timestampMillis: Long) {
        context.settingsDataStore.edit { preferences ->
            preferences[lastUpdateCheckMillisKey] = timestampMillis
        }
    }

    suspend fun persistDisplayFrequenciesAsPercent(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[displayFrequenciesAsPercentKey] = enabled
        }
    }

    suspend fun persistLeftEdgeProfilePickerEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[leftEdgeProfilePickerEnabledKey] = enabled
        }
    }

    suspend fun persistEdgeHandleHeightDp(heightDp: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[edgeHandleHeightDpKey] =
                heightDp.coerceIn(MIN_EDGE_HANDLE_HEIGHT_DP, MAX_EDGE_HANDLE_HEIGHT_DP)
        }
    }

    suspend fun persistEdgeHandleThicknessDp(thicknessDp: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[edgeHandleThicknessDpKey] = normalizeEdgeHandleThicknessDp(thicknessDp)
        }
    }

    suspend fun persistEdgeHandleVerticalPositionPercent(positionPercent: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[edgeHandleVerticalPositionPercentKey] = positionPercent.coerceIn(
                MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
                MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
            )
        }
    }

    suspend fun persistEdgeHandleOpacityPercent(opacityPercent: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[edgeHandleOpacityPercentKey] =
                normalizeEdgeHandleOpacityPercent(opacityPercent)
        }
    }

    suspend fun persistProfileSwitchToastsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[profileSwitchToastsEnabledKey] = enabled
        }
    }

    suspend fun persistProfileSwitchHistoryLimit(limit: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[profileSwitchHistoryLimitKey] =
                limit.coerceIn(MIN_PROFILE_SWITCH_HISTORY_LIMIT, MAX_PROFILE_SWITCH_HISTORY_LIMIT)
        }
    }

    suspend fun persistPrivilegedExecutionMethodId(methodId: String?) {
        context.settingsDataStore.edit { preferences ->
            if (methodId == null) {
                preferences.remove(privilegedExecutionMethodIdKey)
            } else {
                preferences[privilegedExecutionMethodIdKey] = methodId
            }
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

internal fun supportedExecutionMethodId(methodId: String?): String? {
    // NOTE: this whitelist silently drops unknown ids, so any new execution
    // method MUST be listed here or the user's selection will never persist
    // (it reads back as null / "Not selected").
    return methodId?.takeIf {
        it == "pserver-stdout" || it == "root-shell" || it == "jdwp-inject"
    }
}

internal fun normalizeEdgeHandleThicknessDp(thicknessDp: Int): Int {
    return thicknessDp.coerceIn(
        MIN_EDGE_HANDLE_THICKNESS_DP,
        MAX_EDGE_HANDLE_THICKNESS_DP,
    )
}

internal fun normalizeEdgeHandleOpacityPercent(opacityPercent: Int): Int {
    return opacityPercent.coerceIn(
        MIN_EDGE_HANDLE_OPACITY_PERCENT,
        MAX_EDGE_HANDLE_OPACITY_PERCENT,
    )
}
