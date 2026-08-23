package com.aure.clustertune.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

data class ForegroundAppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
)

class ForegroundAppResolver(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun resolve(
        snapshot: VisibleAppSnapshot = VisibleAppWindowEvents.snapshots.value,
        targetDisplayId: Int? = null,
        excludedPackages: Set<String> = emptySet(),
    ): ForegroundAppInfo? {
        val window = selectVisibleAppWindow(snapshot, targetDisplayId, excludedPackages) ?: return null
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
        )
    }

    /** Select the same deterministic candidate used by [resolve]. */
    fun selectPackageName(
        snapshot: VisibleAppSnapshot,
        targetDisplayId: Int? = null,
        excludedPackages: Set<String> = emptySet(),
    ): String? = selectVisibleAppWindow(snapshot, targetDisplayId, excludedPackages)?.packageName

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
): VisibleAppWindow? {
    val windows = if (targetDisplayId != null) {
        snapshot.windowsByDisplay[targetDisplayId].orEmpty()
    } else {
        snapshot.windowsByDisplay.values.asSequence().flatten().toList()
    }
    return windows.asSequence()
        .filterNot { it.packageName in excludedPackages }
        .sortedWith(
            compareByDescending<VisibleAppWindow> { it.isFocused }
                .thenByDescending { it.isActive }
                .thenBy { it.displayId }
                .thenBy { it.packageName },
        )
        .firstOrNull()
}

/** Vendor performance overlays that remain visible above the actual game window. */
internal val VENDOR_GAME_ASSISTANT_PACKAGES = setOf(
    "com.odin.gameassistant",
    "com.ayn.gameassistant",
    "com.rp.gameassistant",
    "com.retroidpocket.gameassistant",
)
