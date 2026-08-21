package com.aure.clustertune.model

enum class AppColorSource {
    SYSTEM,
    CUSTOM_ACCENT,
}

enum class TileInteractionBehavior {
    SHOW_DIALOG,
    SHOW_PROFILE_PICKER,
    CYCLE_PROFILES,
    OPEN_APP,
}

data class AppSettings(
    val colorSource: AppColorSource = AppColorSource.SYSTEM,
    val accentColor: Int = 0xFF3F51B5.toInt(),
    val customAccentColor: Int = 0xFF3F51B5.toInt(),
    val tileTapBehavior: TileInteractionBehavior = TileInteractionBehavior.SHOW_DIALOG,
    val applyLastProfileOnBoot: Boolean = false,
    val sleepProfileEnabled: Boolean = false,
    val sleepProfileId: String? = null,
    val hasPromptedQuickSettingsTile: Boolean = false,
    val isQuickSettingsTileAdded: Boolean = false,
    val automaticUpdateChecksEnabled: Boolean = DEFAULT_AUTOMATIC_UPDATE_CHECKS_ENABLED,
    val updateCheckIntervalDays: Int = 7,
    val includePrereleaseUpdates: Boolean = DEFAULT_INCLUDE_PRERELEASE_UPDATES,
    val lastUpdateCheckMillis: Long = 0L,
    val displayFrequenciesAsPercent: Boolean = false,
    /**
     * Opt-in diagnostics for the wireless-debugging execution method.
     *
     * Off by default so release builds emit nothing at all — neither the in-app
     * buffer nor logcat. Only meaningful while that execution method is in use,
     * which is why the setting is hidden otherwise.
     */
    val wirelessDebugLoggingEnabled: Boolean = false,
    val leftEdgeProfilePickerEnabled: Boolean = false,
    val edgeHandleHeightDp: Int = DEFAULT_EDGE_HANDLE_HEIGHT_DP,
    val edgeHandleThicknessDp: Int = DEFAULT_EDGE_HANDLE_THICKNESS_DP,
    val edgeHandleVerticalPositionPercent: Int = DEFAULT_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
    val edgeHandleOpacityPercent: Int = DEFAULT_EDGE_HANDLE_OPACITY_PERCENT,
    val profileSwitchToastsEnabled: Boolean = true,
    val profileSwitchHistoryLimit: Int = DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT,
    val privilegedExecutionMethodId: String? = null,
)

const val DEFAULT_PROFILE_SWITCH_HISTORY_LIMIT = 100
const val DEFAULT_AUTOMATIC_UPDATE_CHECKS_ENABLED = true
const val DEFAULT_INCLUDE_PRERELEASE_UPDATES = false
const val MIN_PROFILE_SWITCH_HISTORY_LIMIT = 1
const val MAX_PROFILE_SWITCH_HISTORY_LIMIT = 1_000

const val DEFAULT_EDGE_HANDLE_HEIGHT_DP = 72
const val MIN_EDGE_HANDLE_HEIGHT_DP = 48
const val MAX_EDGE_HANDLE_HEIGHT_DP = 200
const val DEFAULT_EDGE_HANDLE_THICKNESS_DP = 10
const val MIN_EDGE_HANDLE_THICKNESS_DP = 2
const val MAX_EDGE_HANDLE_THICKNESS_DP = 24
const val DEFAULT_EDGE_HANDLE_VERTICAL_POSITION_PERCENT = 50
const val MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT = 0
const val MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT = 100
const val DEFAULT_EDGE_HANDLE_OPACITY_PERCENT = 94
const val MIN_EDGE_HANDLE_OPACITY_PERCENT = 0
const val MAX_EDGE_HANDLE_OPACITY_PERCENT = 100
