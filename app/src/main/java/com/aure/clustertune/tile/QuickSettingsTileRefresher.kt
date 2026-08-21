package com.aure.clustertune.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

object QuickSettingsTileRefresher {

    fun requestUpdate(context: Context) {
        val appContext = context.applicationContext
        PerformanceTileService.refreshActiveTile()
        requestListeningState(appContext)
    }

    private fun requestListeningState(context: Context) {
        TileService.requestListeningState(
            context,
            ComponentName(context, PerformanceTileService::class.java),
        )
    }
}
