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
    ): ForegroundAppInfo? {
        // Keep ignored packages as explicit candidates. The overlay uses these
        // transient samples to distinguish a shade transition from a real app
        // change, rather than treating the transition as an unknown/null app.
        val window = selectVisibleAppWindow(snapshot, targetDisplayId) ?: return null
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
    fun selectPackageName(snapshot: VisibleAppSnapshot, targetDisplayId: Int? = null): String? =
        selectVisibleAppWindow(snapshot, targetDisplayId)?.packageName

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
): VisibleAppWindow? {
    val windows = if (targetDisplayId != null) {
        snapshot.windowsByDisplay[targetDisplayId].orEmpty()
    } else {
        snapshot.windowsByDisplay.values.asSequence().flatten().toList()
    }
    return windows.sortedWith(
        compareByDescending<VisibleAppWindow> { it.isFocused }
            .thenByDescending { it.isActive }
            .thenBy { it.displayId }
            .thenBy { it.packageName },
    ).firstOrNull()
}
