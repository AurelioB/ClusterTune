package com.aure.clustertune.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A visible application window, grouped by physical display. */
data class VisibleAppWindow(
    val packageName: String,
    val displayId: Int,
    val isFocused: Boolean = false,
    val isActive: Boolean = false,
)

data class VisibleAppSnapshot(
    val windowsByDisplay: Map<Int, List<VisibleAppWindow>> = emptyMap(),
    val isInteractive: Boolean = false,
) {
    val packages: Set<String> get() = windowsByDisplay.values.flatten().mapTo(linkedSetOf()) { it.packageName }

    companion object { val Empty = VisibleAppSnapshot() }
}

/**
 * Restores the last real window event when an OEM game assistant is the only
 * application window exposed by Accessibility for a display.
 */
internal fun mergeEventFallbackWindows(
    observed: Map<Int, List<VisibleAppWindow>>,
    eventFallbacks: Map<Int, String>,
    obscuringPackages: Set<String>,
): Map<Int, List<VisibleAppWindow>> {
    val merged = observed.mapValuesTo(mutableMapOf()) { (_, windows) -> windows.toMutableList() }
    eventFallbacks.forEach { (displayId, packageName) ->
        val windows = merged.getOrPut(displayId) { mutableListOf() }
        val hasRealWindow = windows.any { it.packageName !in obscuringPackages }
        if (!hasRealWindow && windows.none { it.packageName == packageName }) {
            windows += VisibleAppWindow(
                packageName = packageName,
                displayId = displayId,
                isFocused = false,
                isActive = true,
            )
        }
    }
    return merged
}

/** Deterministic bounded confirmation for displays temporarily omitted by accessibility. */
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
    ): Result {
        val output = observed.toMutableMap()
        var next: Long? = null
        published.toMap().forEach { (id, previous) ->
            if (output[id].orEmpty().isNotEmpty()) {
                deadlines.remove(id)
                return@forEach
            }
            if (!displayOn(id)) {
                deadlines.remove(id)
                published.remove(id)
                return@forEach
            }
            val deadline = deadlines[id] ?: (nowMs + graceMs).also { deadlines[id] = it }
            if (nowMs >= deadline) {
                deadlines.remove(id)
                published.remove(id)
            } else {
                output[id] = previous.map { it.copy(isFocused = false, isActive = false) }
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

/** Process-local event contract consumed by the profile coordinator. */
object VisibleAppWindowEvents {
    private val mutable = MutableStateFlow(VisibleAppSnapshot.Empty)
    val snapshots: StateFlow<VisibleAppSnapshot> = mutable.asStateFlow()

    fun publish(snapshot: VisibleAppSnapshot) {
        if (mutable.value != snapshot) mutable.value = snapshot
    }

    fun clear(isInteractive: Boolean = false) = publish(VisibleAppSnapshot(isInteractive = isInteractive))
}
