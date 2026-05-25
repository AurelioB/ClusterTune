package com.aure.clustertune.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aure.clustertune.AppContainer
import com.aure.clustertune.sleep.SleepProfileMonitorService
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> QuickSettingsTileRefresher.requestUpdate(context)
        }
    }

    private fun handleBootCompleted(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = AppContainer(context)
                val settings = container.settingsStorage.settings.first()
                if (settings.sleepProfileEnabled) {
                    SleepProfileMonitorService.start(context)
                }
                if (!settings.applyLastProfileOnBoot) {
                    return@launch
                }
                container.repository.applyPersistedLastValuesOnBoot()
                QuickSettingsTileRefresher.requestUpdate(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
