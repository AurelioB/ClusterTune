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
import android.os.PowerManager
import android.os.SystemClock
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
    private val foregroundAppTracker = ForegroundAppTracker()
    private val transitionState = AppProfileTransitionState()
    private var lastUsageEventTimestamp: Long = 0L
    private var failedTransition: FailedTransition? = null

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
            val powerManager = getSystemService(PowerManager::class.java)
            if (powerManager?.isInteractive == false) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            if (assignments.isNotEmpty() && !hasUsageStatsPermission(this@AppProfileMonitorService)) {
                delay(POLL_INTERVAL_MS)
                continue
            }
            val foregroundPackage = if (assignments.isEmpty()) null else currentForegroundPackage()
            val action = transitionState.observe(
                nowMs = SystemClock.elapsedRealtime(),
                foregroundPackage = foregroundPackage,
                assignments = assignments,
                transientForeground = foregroundPackage == null || !isLaunchablePackage(foregroundPackage),
            )
            val nowElapsed = SystemClock.elapsedRealtime()
            when (action) {
                is AppProfileTransitionState.Action.Apply -> {
                    val assignment = action.assignment
                    val profile = state.displayProfiles.firstOrNull { it.id == assignment.profileId }
                    val retryKey = "apply:${assignment.packageName}:${assignment.profileId}:${profile?.maxFrequencies}:${assignment.customMaxFrequencies}"
                    if (!shouldAttemptTransition(retryKey, nowElapsed)) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    if (powerManager?.isInteractive == false) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    val profileName = profile?.name ?: if (assignment.isCustom) "Custom" else assignment.profileId ?: "Unknown"
                    val result = container.repository.applyAppProfileTemporarily(assignment)
                    if (result.isSuccess) {
                        clearTransitionFailure()
                        transitionState.onApplied(assignment)
                        val trigger = "App focused: ${assignment.appLabel} (${assignment.packageName})"
                        container.repository.logProfileSwitch(assignment.profileId, profileName, trigger)
                        QuickSettingsTileRefresher.requestUpdate(applicationContext)
                        showProfileToast(profileName)
                    } else {
                        recordTransitionFailure(retryKey, nowElapsed)
                    }
                }
                AppProfileTransitionState.Action.Restore -> {
                    val retryKey = "restore:${state.lastAppliedDisplayProfileId}:${foregroundPackage ?: "unknown"}"
                    if (!shouldAttemptTransition(retryKey, nowElapsed)) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    if (powerManager?.isInteractive == false) {
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    val restoreProfileName = state.lastAppliedDisplayProfileId
                        ?.let { profileId -> state.displayProfiles.firstOrNull { it.id == profileId }?.name }
                        ?: "Manual"
                    val result = container.repository.restoreNormalProfileTemporarily()
                    if (result.isSuccess) {
                        clearTransitionFailure()
                        transitionState.onRestored()
                        val trigger = foregroundPackage
                            ?.let { "Focused app has no assigned profile: $it" }
                            ?: "No foreground app detected"
                        container.repository.logProfileSwitch(state.lastAppliedDisplayProfileId, restoreProfileName, trigger)
                        QuickSettingsTileRefresher.requestUpdate(applicationContext)
                        showProfileToast(restoreProfileName)
                    } else {
                        recordTransitionFailure(retryKey, nowElapsed)
                    }
                }
                AppProfileTransitionState.Action.None -> Unit
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun shouldAttemptTransition(key: String, nowElapsed: Long): Boolean {
        val failure = failedTransition ?: return true
        return failure.key != key || nowElapsed >= failure.nextAttemptAt
    }

    private fun recordTransitionFailure(key: String, nowElapsed: Long) {
        val previous = failedTransition?.takeIf { it.key == key }
        val failures = (previous?.failures ?: 0) + 1
        if (failures >= MAX_TRANSITION_FAILURES) {
            failedTransition = FailedTransition(key, failures, Long.MAX_VALUE)
            return
        }
        val backoff = POLL_INTERVAL_MS shl (failures - 1).coerceAtMost(2)
        failedTransition = FailedTransition(key, failures, nowElapsed + backoff)
    }

    private fun clearTransitionFailure() {
        failedTransition = null
    }

    private fun isLaunchablePackage(packageName: String): Boolean =
        runCatching { packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun currentForegroundPackage(): String? {
        val usageStatsManager = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val queryStart = if (lastUsageEventTimestamp == 0L) {
            now - INITIAL_LOOKBACK_MS
        } else {
            maxOf(now - LOOKBACK_MS, lastUsageEventTimestamp + 1)
        }
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
                        eventTimestamp = event.timeStamp,
                    )
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    foregroundAppTracker.onActivityPaused(
                        packageName = event.packageName,
                        className = event.className,
                        eventTimestamp = event.timeStamp,
                    )
                }
                UsageEvents.Event.ACTIVITY_STOPPED -> foregroundAppTracker.onActivityStopped(
                    event.packageName,
                    event.className,
                    event.timeStamp,
                )
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
        private const val MAX_TRANSITION_FAILURES = 3
        private const val LOOKBACK_MS = 30_000L
        private const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1_000L

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

    private data class FailedTransition(
        val key: String,
        val failures: Int,
        val nextAttemptAt: Long,
    )
}
