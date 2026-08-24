package com.aure.clustertune.model

import android.graphics.drawable.Drawable

data class AppProfileAssignment(
    val packageName: String,
    val appLabel: String,
    val profileId: String? = null,
    val customMaxFrequencies: Map<Int, Int> = emptyMap(),
    val customGpuMaxFrequencyHz: Int? = null,
    val autoTuneTargetFps: Int? = null,
) {
    val isCustom: Boolean
        get() = customMaxFrequencies.isNotEmpty() || customGpuMaxFrequencyHz != null

    val isAutoTune: Boolean
        get() = autoTuneTargetFps != null

    /** Exactly one supported reusable, custom, or auto-tune target must be present. */
    val hasValidTarget: Boolean
        get() {
            val targetCount = listOf(profileId != null, isCustom, isAutoTune).count { it }
            val hasSupportedAutoTuneTarget = autoTuneTargetFps
                ?.let { it in MIN_AUTO_TUNE_TARGET_FPS..MAX_AUTO_TUNE_TARGET_FPS } == true
            return targetCount == 1 && (!isAutoTune || hasSupportedAutoTuneTarget)
        }
}

const val MIN_AUTO_TUNE_TARGET_FPS = 15
const val MAX_AUTO_TUNE_TARGET_FPS = 240
val AUTO_TUNE_TARGET_FPS_PRESETS = listOf(30, 60, 120)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)
