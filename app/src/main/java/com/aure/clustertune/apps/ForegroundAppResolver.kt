package com.aure.clustertune.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class ForegroundAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val displayId: Int? = null,
    val currentRefreshRateFps: Int? = null,
)

class ForegroundAppResolver(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun resolve(
        snapshot: VisibleAppSnapshot = VisibleAppWindowEvents.snapshots.value,
        targetDisplayId: Int? = null,
        excludedPackages: Set<String> = emptySet(),
        preferredPackageName: String? = null,
    ): ForegroundAppInfo? {
        val window = selectVisibleAppWindow(
            snapshot = snapshot,
            targetDisplayId = targetDisplayId,
            excludedPackages = excludedPackages,
            preferredPackageName = preferredPackageName,
        ) ?: return null
        val packageName = window.packageName
        val applicationInfo = applicationInfo(packageName)
        return ForegroundAppInfo(
            packageName = packageName,
            label = applicationInfo?.let {
                runCatching { it.loadLabel(packageManager).toString() }
                    .getOrNull()
                    ?.takeIf(String::isNotBlank)
            } ?: packageName,
            icon = applicationInfo?.let {
                runCatching { it.loadIcon(packageManager) }.getOrNull()
            },
            displayId = window.displayId,
            currentRefreshRateFps = snapshot.refreshRateFpsByDisplay[window.displayId],
        )
    }

    /** Select the same deterministic candidate used by [resolve]. */
    fun selectPackageName(
        snapshot: VisibleAppSnapshot,
        targetDisplayId: Int? = null,
        excludedPackages: Set<String> = emptySet(),
        preferredPackageName: String? = null,
    ): String? = selectVisibleAppWindow(
        snapshot = snapshot,
        targetDisplayId = targetDisplayId,
        excludedPackages = excludedPackages,
        preferredPackageName = preferredPackageName,
    )?.packageName

    private fun applicationInfo(packageName: String) = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }.getOrNull()
}

internal fun selectVisibleAppWindow(
    snapshot: VisibleAppSnapshot,
    targetDisplayId: Int? = null,
    excludedPackages: Set<String> = emptySet(),
    preferredPackageName: String? = null,
): VisibleAppWindow? {
    return snapshot.visibleWindows(targetDisplayId)
        .filterNot { it.packageName in excludedPackages }
        .sortedWith(snapshot.visibleWindowComparator(preferredPackageName))
        .firstOrNull()
}

/** Selects the strongest visible window for one package, optionally on one display. */
internal fun selectVisibleAppWindowForPackage(
    snapshot: VisibleAppSnapshot,
    packageName: String,
    targetDisplayId: Int? = null,
): VisibleAppWindow? = snapshot.visibleWindows(targetDisplayId)
    .filter { it.packageName == packageName }
    .sortedWith(snapshot.visibleWindowComparator())
    .firstOrNull()

private fun VisibleAppSnapshot.visibleWindows(targetDisplayId: Int?): Sequence<VisibleAppWindow> =
    if (targetDisplayId != null) {
        windowsByDisplay[targetDisplayId].orEmpty().asSequence()
    } else {
        windowsByDisplay.values.asSequence().flatten()
    }

private fun VisibleAppSnapshot.visibleWindowComparator(
    preferredPackageName: String? = null,
): Comparator<VisibleAppWindow> =
    compareByDescending<VisibleAppWindow> { it.isFocused }
        .thenByDescending { it.isActive }
        .thenByDescending { window ->
            mostRecentAppIdentity?.let { recent ->
                recent.displayId == window.displayId && recent.packageName == window.packageName
            } == true
        }
        .thenByDescending { recentPackageByDisplay[it.displayId] == it.packageName }
        // Keep an established picker context only after focus, activity, and the latest
        // real window event are tied. This prevents a still-visible old app from masking
        // a genuine foreground transition on OEMs that report ambiguous window flags.
        .thenByDescending {
            preferredPackageName != null && it.packageName == preferredPackageName
        }
        .thenBy { it.displayId }
        .thenBy { it.packageName }

/** Vendor performance overlays that remain visible above the actual game window. */
internal val VENDOR_GAME_ASSISTANT_PACKAGES = setOf(
    "com.odin.gameassistant",
    "com.ayn.gameassistant",
    "com.rp.gameassistant",
    "com.retroidpocket.gameassistant",
)
