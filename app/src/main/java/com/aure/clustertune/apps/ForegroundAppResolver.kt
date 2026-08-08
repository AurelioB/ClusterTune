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
        ignoredPackages: Set<String> = emptySet(),
    ): ForegroundAppInfo? {
        val window = snapshot.windowsByDisplay.values
            .asSequence()
            .flatten()
            .filterNot { it.packageName in ignoredPackages }
            .sortedWith(
                compareByDescending<VisibleAppWindow> { it.isFocused }
                    .thenByDescending { it.isActive }
                    .thenBy { it.displayId }
                    .thenBy { it.packageName },
            )
            .firstOrNull() ?: return null
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
