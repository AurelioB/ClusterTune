package com.aure.clustertune.boot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aure.clustertune.AppContainer
import com.aure.clustertune.MainActivity
import com.aure.clustertune.R
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restores the user's normal profile once privileged services become ready after boot. */
class BootProfileRestoreService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val container by lazy { AppContainer(this) }
    private var restoreJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (restoreJob?.isActive == true) return START_NOT_STICKY
        restoreJob = serviceScope.launch {
            try {
                val settings = container.settingsStorage.settings.first()
                if (!settings.applyLastProfileOnBoot) return@launch

                // AppContainer observes this setting asynchronously for normal app startup.
                // Boot restoration cannot race that collector before its first host launch.
                container.privilegedExecutionResolver.setConfiguredMethodId(
                    settings.privilegedExecutionMethodId,
                )
                val result = retryBootRestore(
                    attemptDelaysMs = ATTEMPT_DELAYS_MS,
                    wait = { delay(it) },
                ) { attempt ->
                    container.repository.applyPersistedLastValuesOnBoot()
                        .onFailure { error ->
                            Log.w(TAG, "Boot profile restore attempt $attempt failed", error)
                        }
                }
                if (result.isSuccess) {
                    QuickSettingsTileRefresher.requestUpdate(applicationContext)
                } else {
                    Log.e(TAG, "Unable to restore the last profile after boot", result.exceptionOrNull())
                }
            } finally {
                ServiceCompat.stopForeground(this@BootProfileRestoreService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Profile restoration",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = "Restores the selected performance profile after startup."
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("Restoring performance profile")
            .setContentText("Waiting for privileged access after startup.")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    companion object {
        private const val TAG = "BootProfileRestore"
        private const val CHANNEL_ID = "boot_profile_restore"
        private const val NOTIFICATION_ID = 51

        // Covers normal OEM/root startup lag without leaving a persistent worker behind.
        internal val ATTEMPT_DELAYS_MS = listOf(0L, 1_500L, 3_500L, 7_000L, 15_000L)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BootProfileRestoreService::class.java),
            )
        }
    }
}
