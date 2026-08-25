package com.aure.clustertune.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Hard, nonrenewing lifetime for a foreground-app picker target. */
internal const val PICKER_FOREGROUND_LEASE_DURATION_MS = 120_000L

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

/** Picker-only handoff that never contributes a package to app-profile automation. */
data class PickerForegroundAppHandoff(
    val packageName: String,
    /** A new picker may consume the handoff only during this short bridge window. */
    val seedExpiresAtUptimeMs: Long,
    /** Absolute deadline inherited by a picker that consumes the handoff. */
    val leaseExpiresAtUptimeMs: Long,
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
    /** Last verified app briefly available to a picker while system/overlay windows own Accessibility. */
    val pickerHandoffByDisplay: Map<Int, PickerForegroundAppHandoff> = emptyMap(),
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
    ): Result {
        val output = observed.toMutableMap()
        var next: Long? = null
        published.toMap().forEach { (id, previous) ->
            val current = output[id].orEmpty()
            val currentIsOnlyObscuring = current.isNotEmpty() &&
                current.all { it.packageName in obscuringPackages }
            val previousUnobscured = previous.filterNot { it.packageName in obscuringPackages }
            val retained = if (current.isEmpty()) previous else previousUnobscured
            val previousAppIsNowObscured = currentIsOnlyObscuring && previousUnobscured.isNotEmpty()
            if (current.isNotEmpty() && !previousAppIsNowObscured) {
                deadlines.remove(id)
                return@forEach
            }
            if (!displayOn(id)) {
                deadlines.remove(id)
                published.remove(id)
                output.remove(id)
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
 * Moves a verified real app into a fixed picker-only handoff when Accessibility
 * stops exposing it. Repeated empty or ignored observations cannot renew it.
 */
internal class PickerForegroundAppHandoffTracker(
    private val seedDurationMs: Long,
    private val leaseDurationMs: Long,
) {
    private val lastVerifiedPackages = mutableMapOf<Int, String>()
    private val handoffs = mutableMapOf<Int, PickerForegroundAppHandoff>()

    fun update(
        verifiedPackageByDisplay: Map<Int, String>,
        displayOn: (Int) -> Boolean,
        nowMs: Long,
    ): Map<Int, PickerForegroundAppHandoff> {
        verifiedPackageByDisplay.forEach { (displayId, packageName) ->
            if (packageName.isNotBlank() && displayOn(displayId)) {
                lastVerifiedPackages[displayId] = packageName
                handoffs.remove(displayId)
            }
        }
        (lastVerifiedPackages.keys + handoffs.keys).forEach { displayId ->
            if (!displayOn(displayId)) {
                removeDisplay(displayId)
                return@forEach
            }
            if (displayId in verifiedPackageByDisplay) return@forEach
            val existing = handoffs[displayId]
            if (existing != null && nowMs < existing.leaseExpiresAtUptimeMs) return@forEach
            handoffs.remove(displayId)
            lastVerifiedPackages.remove(displayId)?.let { packageName ->
                handoffs[displayId] = PickerForegroundAppHandoff(
                    packageName = packageName,
                    seedExpiresAtUptimeMs = nowMs + seedDurationMs,
                    leaseExpiresAtUptimeMs = nowMs + leaseDurationMs,
                )
            }
        }
        return handoffs
            .filterValues { nowMs < it.leaseExpiresAtUptimeMs }
            .toSortedMap()
    }

    fun removeDisplay(displayId: Int) {
        lastVerifiedPackages.remove(displayId)
        handoffs.remove(displayId)
    }

    fun clear() {
        lastVerifiedPackages.clear()
        handoffs.clear()
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
