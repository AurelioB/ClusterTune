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

/** Stable identity of one Accessibility window on one physical display. */
internal data class AccessibilityWindowIdentity(
    val displayId: Int,
    val windowId: Int,
)

/** Remembers only useful packages that were resolved for an exact Accessibility window. */
internal class AccessibilityWindowPackageCache {
    private val packagesByWindow = mutableMapOf<AccessibilityWindowIdentity, String>()

    fun record(identity: AccessibilityWindowIdentity, packageName: String) {
        if (identity.displayId >= 0 && identity.windowId >= 0 && packageName.isNotBlank()) {
            packagesByWindow[identity] = packageName
        }
    }

    fun remove(identity: AccessibilityWindowIdentity) {
        packagesByWindow.remove(identity)
    }

    fun resolvePackage(
        identity: AccessibilityWindowIdentity?,
        resolvedPackageName: String?,
    ): String? = resolvedPackageName?.takeIf { it.isNotBlank() }
        ?: identity?.let(packagesByWindow::get)

    fun retainOnly(observedIdentities: Set<AccessibilityWindowIdentity>) {
        packagesByWindow.keys.retainAll(observedIdentities)
    }

    fun removeDisplay(displayId: Int) {
        packagesByWindow.keys.removeAll { it.displayId == displayId }
    }

    fun clear() = packagesByWindow.clear()
}

data class RecentAppIdentity(
    val displayId: Int,
    val packageName: String,
)

data class VisibleAppSnapshot(
    val windowsByDisplay: Map<Int, List<VisibleAppWindow>> = emptyMap(),
    val isInteractive: Boolean = false,
    /** Last real window-state event per display, used when OEM focus flags are ambiguous. */
    val recentPackageByDisplay: Map<Int, String> = emptyMap(),
    /** Globally latest real window-state event, used to break equal cross-display focus ties. */
    val mostRecentAppIdentity: RecentAppIdentity? = null,
    /** Nominal current refresh rate for each connected display, rounded to whole FPS. */
    val refreshRateFpsByDisplay: Map<Int, Int> = emptyMap(),
    /** Latest real foreground-app package per display, used only by foreground-app pickers. */
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

/**
 * Selects a stable fallback after all useful windows have been enumerated.
 * Candidates are ordered by focus, activity, prior event/cache recency, then package.
 */
internal fun selectObservedFallbackPackage(
    candidates: List<VisibleAppWindow>,
    existingPackageName: String?,
): String? = candidates
    .sortedWith(
        compareByDescending<VisibleAppWindow> { it.isFocused }
            .thenByDescending { it.isActive }
            .thenByDescending { it.packageName == existingPackageName }
            .thenBy { it.packageName },
    )
    .firstOrNull()
    ?.packageName

/**
 * Deterministic bounded confirmation for apps temporarily omitted by Accessibility,
 * including displays represented only by a known obscuring window.
 */
internal class VisibleWindowDisappearanceTracker(
    private val graceMs: Long = 300L,
) {
    data class Result(val windowsByDisplay: Map<Int, List<VisibleAppWindow>>, val nextDeadlineMs: Long?)
    private val published = mutableMapOf<Int, List<VisibleAppWindow>>()
    private val deadlines = mutableMapOf<Int, Long>()

    fun stabilize(
        observed: Map<Int, List<VisibleAppWindow>>,
        displayOn: (Int) -> Boolean,
        nowMs: Long,
        obscuringPackages: Set<String> = emptySet(),
        transientlyCoveredDisplays: Set<Int> = emptySet(),
    ): Result {
        val output = observed.filterKeys(displayOn).toMutableMap()
        var next: Long? = null
        published.toMap().forEach { (id, previous) ->
            if (!displayOn(id)) {
                deadlines.remove(id)
                published.remove(id)
                output.remove(id)
                return@forEach
            }
            val current = output[id].orEmpty()
            val hasNewApp = current.any { candidate ->
                candidate.packageName !in obscuringPackages &&
                    previous.none { it.packageName == candidate.packageName }
            }
            if (id in transientlyCoveredDisplays && !hasNewApp) {
                deadlines.remove(id)
                output[id] = previous + current.filter { candidate ->
                    previous.none { it.packageName == candidate.packageName }
                }
                return@forEach
            }
            val currentIsOnlyObscuring = current.isNotEmpty() &&
                current.all { it.packageName in obscuringPackages }
            val previousUnobscured = previous.filterNot { it.packageName in obscuringPackages }
            val retained = if (current.isEmpty()) previous else previousUnobscured
            val previousAppIsNowObscured = currentIsOnlyObscuring && previousUnobscured.isNotEmpty()
            if (current.isNotEmpty() && !previousAppIsNowObscured) {
                deadlines.remove(id)
                return@forEach
            }
            val deadline = deadlines[id] ?: (nowMs + graceMs).also { deadlines[id] = it }
            if (nowMs >= deadline) {
                deadlines.remove(id)
                published.remove(id)
            } else {
                output[id] = current + retained.map { it.copy(isFocused = false, isActive = false) }
                next = minOf(next ?: deadline, deadline)
            }
        }
        val normalized = output.filterValues { it.isNotEmpty() }.toSortedMap()
        published.clear()
        published.putAll(normalized)
        return Result(normalized, next)
    }

    fun clear() {
        published.clear()
        deadlines.clear()
    }

    fun pause() {
        deadlines.clear()
    }

    fun removeDisplay(displayId: Int) {
        published.remove(displayId)
        deadlines.remove(displayId)
    }
}

/**
 * Keeps the latest real app event for the picker without fabricating an
 * application window for app-profile automation. A fresh display-qualified
 * event briefly wins over lagging enumeration; current windows then resume as
 * a correction path if a later event is missed.
 */
internal class PickerForegroundPackageTracker(
    private val eventProtectionMs: Long = 1_000L,
) {
    private val packageByDisplay = mutableMapOf<Int, String>()
    private val eventProtectedUntilByDisplay = mutableMapOf<Int, Long>()

    fun recordWindowStateEvent(displayId: Int, packageName: String, nowMs: Long) {
        if (displayId < 0 || packageName.isBlank()) return
        packageByDisplay[displayId] = packageName
        eventProtectedUntilByDisplay[displayId] = nowMs + eventProtectionMs
    }

    fun updateFromEnumeration(
        verifiedPackageByDisplay: Map<Int, String>,
        displayOn: (Int) -> Boolean,
        nowMs: Long,
    ): Map<Int, String> {
        (packageByDisplay.keys + verifiedPackageByDisplay.keys).forEach { displayId ->
            if (displayId < 0 || !displayOn(displayId)) {
                removeDisplay(displayId)
                return@forEach
            }
            val packageName = verifiedPackageByDisplay[displayId]?.takeIf { it.isNotBlank() }
            if (
                packageName != null &&
                nowMs >= (eventProtectedUntilByDisplay[displayId] ?: Long.MIN_VALUE)
            ) {
                packageByDisplay[displayId] = packageName
            }
        }
        return packageByDisplay.toSortedMap()
    }

    fun removeDisplay(displayId: Int) {
        packageByDisplay.remove(displayId)
        eventProtectedUntilByDisplay.remove(displayId)
    }

    fun clear() {
        packageByDisplay.clear()
        eventProtectedUntilByDisplay.clear()
    }
}

/** Process-local event contract consumed by the profile coordinator. */
object VisibleAppWindowEvents {
    private val mutable = MutableStateFlow(VisibleAppSnapshot.Empty)
    val snapshots: StateFlow<VisibleAppSnapshot> = mutable.asStateFlow()

    fun publish(snapshot: VisibleAppSnapshot) {
        if (mutable.value != snapshot) mutable.value = snapshot
    }

    fun clear(isInteractive: Boolean = false) = publish(VisibleAppSnapshot(isInteractive = isInteractive))
}
