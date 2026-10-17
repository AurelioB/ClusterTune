package com.aure.clustertune.apps

import com.aure.clustertune.root.host.HostAppWindow

/** Normalize task observations into the existing contract shared by automation and pickers. */
internal fun privilegedAppSnapshot(
    tasks: List<HostAppWindow>,
    interactive: Boolean,
    refreshRates: Map<Int, Int>,
    excludedPackages: Set<String>,
    activeDisplayIds: Set<Int> = refreshRates.keys,
): VisibleAppSnapshot {
    if (!interactive) return VisibleAppSnapshot.Empty
    val ordered = tasks.map { task ->
        // Permission dialogs and vendor panels can temporarily be the top activity in the
        // game's own task. Preserve its known base app instead of assigning the panel.
        if (task.packageName in TRANSIENT_APP_WINDOW_PACKAGES || task.packageName in VENDOR_GAME_ASSISTANT_PACKAGES) {
            task.basePackageName?.let { task.copy(packageName = it, focused = false) } ?: task
        } else task
    }.filter {
        it.displayId in activeDisplayIds && it.packageName !in excludedPackages
    }.sortedWith(compareByDescending<HostAppWindow> { it.focused }.thenByDescending { it.lastActiveTime }
        .thenBy { it.displayId }.thenBy { it.packageName })
    val byDisplay = ordered.groupBy { it.displayId }.mapValues { (displayId, windows) ->
        windows.distinctBy { it.packageName }.mapIndexed { index, task ->
            VisibleAppWindow(task.packageName, displayId, task.focused, isActive = index == 0)
        }
    }.toSortedMap()
    val recent = byDisplay.mapValues { (_, windows) -> windows.first().packageName }
    return VisibleAppSnapshot(
        windowsByDisplay = byDisplay,
        isInteractive = true,
        recentPackageByDisplay = recent,
        mostRecentAppIdentity = ordered.firstOrNull()?.let { RecentAppIdentity(it.displayId, it.packageName) },
        refreshRateFpsByDisplay = refreshRates.toSortedMap(),
        pickerPackageByDisplay = recent,
    )
}
