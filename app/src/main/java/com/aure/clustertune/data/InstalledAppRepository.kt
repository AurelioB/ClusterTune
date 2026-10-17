package com.aure.clustertune.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.aure.clustertune.model.InstalledAppInfo

class InstalledAppRepository(
    context: Context,
    private val recentPackages: () -> Result<List<String>>,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    fun listLaunchableApps(): List<InstalledAppInfo> {
        return launchableAppsByPackage()
            .values
            .asSequence()
            .map { appInfo -> appInfo.toInstalledAppInfo() }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun launchableAppsByPackage(): Map<String, android.content.pm.ApplicationInfo> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .map { resolveInfo -> resolveInfo.activityInfo.applicationInfo }
            .filter { appInfo -> appInfo.enabled }
            .distinctBy { appInfo -> appInfo.packageName }
            .associateBy { appInfo -> appInfo.packageName }
    }

    fun listRecentActiveApps(limit: Int = RECENT_APPS_LIMIT): List<InstalledAppInfo> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val packages = recentPackages().getOrElse {
            // Keep the full app picker usable when privileged history is temporarily unavailable.
            Log.w("InstalledAppRepository", "Unable to read recent apps", it)
            return emptyList()
        }
        val launchableAppsByPackage = launchableAppsByPackage()
        return packages.distinct()
            .mapNotNull { launchableAppsByPackage[it]?.toInstalledAppInfo() }
            .take(limit)
    }

    private fun android.content.pm.ApplicationInfo.toInstalledAppInfo(): InstalledAppInfo {
        return InstalledAppInfo(
            packageName = packageName,
            label = loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: packageName,
            icon = loadIcon(packageManager),
        )
    }

    private companion object {
        private const val RECENT_APPS_LIMIT = 5
    }
}
