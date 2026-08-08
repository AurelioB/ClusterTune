package com.aure.clustertune.permissions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.aure.clustertune.apps.AppProfileAccessibilityService

object AppProfileAccessibilityAccess {
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
