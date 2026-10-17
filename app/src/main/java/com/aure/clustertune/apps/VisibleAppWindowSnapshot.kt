package com.aure.clustertune.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** A visible application window, grouped by physical display. */
data class VisibleAppWindow(
    val packageName: String,
    val displayId: Int,
    val isFocused: Boolean = false,
    val isActive: Boolean = false,
)

data class RecentAppIdentity(
    val displayId: Int,
    val packageName: String,
)

data class VisibleAppSnapshot(
    val windowsByDisplay: Map<Int, List<VisibleAppWindow>> = emptyMap(),
    val isInteractive: Boolean = false,
    /** False while waiting for a fresh task observation, including immediately after wake. */
    val hasAppObservation: Boolean = true,
    /** Most recently active visible app per display. */
    val recentPackageByDisplay: Map<Int, String> = emptyMap(),
    /** Most recently active visible app, used to break equal cross-display focus ties. */
    val mostRecentAppIdentity: RecentAppIdentity? = null,
    /** Nominal current refresh rate for each connected display, rounded to whole FPS. */
    val refreshRateFpsByDisplay: Map<Int, Int> = emptyMap(),
    /** Foreground-app package per display, used by foreground-app pickers. */
    val pickerPackageByDisplay: Map<Int, String> = emptyMap(),
) {
    val packages: Set<String> get() = windowsByDisplay.values.flatten().mapTo(linkedSetOf()) { it.packageName }

    companion object { val Empty = VisibleAppSnapshot() }
}

/** Converts values such as 59.94 Hz to their nominal whole-frame refresh rate. */
internal fun nominalDisplayRefreshRateFps(refreshRateHz: Float): Int? =
    refreshRateHz
        .takeIf { it.isFinite() && it > 0f }
        ?.roundToInt()
        ?.takeIf { it > 0 }

/** System-owned application windows that cannot represent a user foreground app. */
internal val TRANSIENT_APP_WINDOW_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
)

/** Process-local event contract consumed by the profile coordinator. */
object VisibleAppWindowEvents {
    private val mutable = MutableStateFlow(VisibleAppSnapshot.Empty)
    val snapshots: StateFlow<VisibleAppSnapshot> = mutable.asStateFlow()

    fun publish(snapshot: VisibleAppSnapshot) {
        if (mutable.value != snapshot) mutable.value = snapshot
    }

    fun clear(isInteractive: Boolean = false) = publish(VisibleAppSnapshot(isInteractive = isInteractive))
}
