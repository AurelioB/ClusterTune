package com.aure.clustertune.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aure.clustertune.apps.AppProfileAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppProfileAccessibilityAccess {
    private val connectedService = MutableStateFlow(false)
    val isConnected = connectedService.asStateFlow()
    private val healthyService = MutableStateFlow(true)
    val isHealthy = healthyService.asStateFlow()

    internal fun setHealthy(healthy: Boolean) { healthyService.value = healthy }

    internal fun setConnected(connected: Boolean) {
        connectedService.value = connected
    }

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = ComponentName(context, AppProfileAccessibilityService::class.java)
        return enabled.split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == component }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
