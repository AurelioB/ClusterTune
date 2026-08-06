package com.aure.clustertune.apps

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.aure.clustertune.AppContainer
import com.aure.clustertune.R
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.SingleToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class AppProfileMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private lateinit var container: AppContainer
    private var activeAssignedPackage: String? = null
    private val foregroundAppTracker = ForegroundAppTracker()
    private var lastUsageEventTimestamp: Long = 0L

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        monitorJob = scope.launch { monitorForegroundApps() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun monitorForegroundApps() {
        while (currentCoroutineContext().isActive) {
            val state = container.repository.observeState().first()
            val assignments = state.appProfileAssignments
            if (assignments.isEmpty()) {
                if (activeAssignedPackage != null) {
                    val restoreProfileName = state.lastAppliedDisplayProfileId
                        ?.let { profileId -> state.displayProfiles.firstOrNull { it.id == profileId }?.name }
                        ?: "Manual"
                    activeAssignedPackage = null
                    container.repository.restoreNormalProfileTemporarily().onSuccess {
                        val trigger = "No assigned app focused; restored previous profile"
                        container.repository.logProfileSwitch(state.lastAppliedDisplayProfileId, restoreProfileName, trigger)
                        QuickSettingsTileRefresher.requestUpdate(applicationContext)
                        showProfileToast(restoreProfileName)
                    }
                }
                delay(POLL_INTERVAL_MS)
                continue
            }
            if (!hasUsageStatsPermission(this@AppProfileMonitorService)) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            val foregroundPackage = currentForegroundPackage()
            val assignment = assignments.firstOrNull { it.packageName == foregroundPackage }
            when {
                assignment != null && assignment.packageName != activeAssignedPackage -> {
                    val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId }
                    val profileName = profile?.name ?: if (assignment.isCustom) "Custom" else assignment.profileId ?: "Unknown"
                    activeAssignedPackage = assignment.packageName
                    container.repository.applyAppProfileTemporarily(assignment).onSuccess {
                        val trigger = "App focused: ${assignment.appLabel} (${assignment.packageName})"
                        container.repository.logProfileSwitch(assignment.profileId, profileName, trigger)
                        QuickSettingsTileRefresher.requestUpdate(applicationContext)
                        showProfileToast(profileName)
                    }
                }
                assignment == null && activeAssignedPackage != null -> {
                    val restoreProfileName = state.lastAppliedDisplayProfileId
                        ?.let { profileId -> state.displayProfiles.firstOrNull { it.id == profileId }?.name }
                        ?: "Manual"
                    activeAssignedPackage = null
                    container.repository.restoreNormalProfileTemporarily().onSuccess {
                        val trigger = foregroundPackage
                            ?.let { "Focused app has no assigned profile: $it" }
                            ?: "No foreground app detected"
                        container.repository.logProfileSwitch(state.lastAppliedDisplayProfileId, restoreProfileName, trigger)
                        QuickSettingsTileRefresher.requestUpdate(applicationContext)
                        showProfileToast(restoreProfileName)
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun currentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val queryStart = maxOf(now - LOOKBACK_MS, lastUsageEventTimestamp + 1)
        val events = usageStatsManager.queryEvents(queryStart, now)
        val event = UsageEvents.Event()
        var newestEventTimestamp = lastUsageEventTimestamp
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp < queryStart) continue
            newestEventTimestamp = maxOf(newestEventTimestamp, event.timeStamp)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    foregroundAppTracker.onActivityResumed(
                        packageName = event.packageName,
                        className = event.className,
                    )
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    foregroundAppTracker.onActivityPaused(
                        packageName = event.packageName,
                        className = event.className,
                    )
                }
                UsageEvents.Event.ACTIVITY_STOPPED -> foregroundAppTracker.onActivityStopped()
            }
        }
        lastUsageEventTimestamp = newestEventTimestamp
        return foregroundAppTracker.foregroundPackage
    }

    private fun showProfileToast(profileName: String) {
        scope.launch {
            if (!container.settingsStorage.settings.first().profileSwitchToastsEnabled) return@launch
            SingleToast.show(applicationContext, profileName)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "App profile automation",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("ClusterTune app profiles")
            .setContentText("Watching focused apps to apply assigned profiles")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "app_profile_monitor"
        private const val NOTIFICATION_ID = 2002
        private const val POLL_INTERVAL_MS = 750L
        private const val LOOKBACK_MS = 30_000L

        fun start(context: Context) {
            if (!hasUsageStatsPermission(context)) return
            val intent = Intent(context, AppProfileMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AppProfileMonitorService::class.java))
        }

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
