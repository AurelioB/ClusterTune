package com.aure.clustertune.sleep

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.aure.clustertune.AppContainer
import com.aure.clustertune.notifications.AppForegroundNotification
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SleepProfileMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitionMutex = Mutex()
    private val container by lazy { AppContainer(this) }
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> applySleepProfile()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> restorePreSleepState()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppForegroundNotification.start(this)
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (!settings.sleepProfileEnabled) {
                stopSelf()
                return@launch
            }
            // START_STICKY recreation and package replacement may not deliver
            // a matching screen broadcast. Reconcile persisted state here so
            // a sleep cap cannot remain stranded across process death.
            val powerManager = getSystemService<PowerManager>()
            if (powerManager?.isInteractive == true) {
                restorePreSleepState()
            } else if (settings.sleepProfileId != null) {
                applySleepProfile()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun applySleepProfile() {
        serviceScope.launch {
            transitionMutex.withLock {
                val settings = container.settingsStorage.settings.first()
                val profileId = settings.sleepProfileId
                if (!settings.sleepProfileEnabled || profileId == null) return@withLock
                val result = container.repository.applySleepProfile(profileId)
                if (result.isSuccess) QuickSettingsTileRefresher.requestUpdate(applicationContext)
            }
        }
    }

    private fun restorePreSleepState() {
        serviceScope.launch {
            transitionMutex.withLock {
                val settings = container.settingsStorage.settings.first()
                if (!settings.sleepProfileEnabled) return@withLock
                val result = container.repository.restorePreSleepState()
                if (result.isSuccess) QuickSettingsTileRefresher.requestUpdate(applicationContext)
            }
        }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SleepProfileMonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SleepProfileMonitorService::class.java))
        }
    }
}
