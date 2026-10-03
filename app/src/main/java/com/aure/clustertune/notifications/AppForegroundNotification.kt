package com.aure.clustertune.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.aure.clustertune.MainActivity
import com.aure.clustertune.R

/** The single notification shared by ClusterTune's app-owned foreground services. */
object AppForegroundNotification {
    const val ID = 41

    private const val CHANNEL_ID = "clustertune_overlays"
    private val OBSOLETE_CHANNEL_IDS = listOf(
        "sleep_profile_monitoring",
        "boot_profile_restore",
    )

    /** Android retains this shared notification until the last service using its ID stops. */
    fun start(service: Service) {
        ServiceCompat.startForeground(
            service,
            ID,
            create(service),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    fun create(context: Context): Notification {
        context.getSystemService<NotificationManager>()?.let { manager ->
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "ClusterTune background activity",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    description = "Shows when ClusterTune background features are active."
                },
            )
            OBSOLETE_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("ClusterTune is tuning your clusters")
            .setContentText("Background features are active.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }
}
