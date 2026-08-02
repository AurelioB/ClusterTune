package com.aure.clustertune.model

import android.graphics.drawable.Drawable

data class AppProfileAssignment(
    val packageName: String,
    val appLabel: String,
    val profileId: String? = null,
    val customMaxFrequencies: Map<Int, Int> = emptyMap(),
) {
    val isCustom: Boolean
        get() = customMaxFrequencies.isNotEmpty()

    /** Exactly one of a reusable profile reference or a custom snapshot must be present. */
    val hasValidTarget: Boolean
        get() = (profileId != null) xor isCustom
}

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)
