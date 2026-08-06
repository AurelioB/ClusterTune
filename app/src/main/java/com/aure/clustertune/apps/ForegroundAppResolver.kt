package com.aure.clustertune.apps

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
    private val usageStatsManager = appContext.getSystemService(UsageStatsManager::class.java)
    private val packageManager = appContext.packageManager

    @Suppress("DEPRECATION")
    fun resolve(): ForegroundAppInfo? {
        if (!AppProfileMonitorService.hasUsageStatsPermission(appContext)) return null

        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - EVENT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        val tracker = ForegroundAppTracker()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    tracker.onActivityResumed(event.packageName, event.className, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    tracker.onActivityPaused(event.packageName, event.className, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_STOPPED -> tracker.onActivityStopped(
                    event.packageName,
                    event.className,
                    event.timeStamp,
                )
            }
        }

        val packageName = tracker.foregroundPackage ?: return null
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

    companion object {
        private const val EVENT_LOOKBACK_MS = 24 * 60 * 60 * 1_000L
    }
}
