package com.aure.clustertune.permissions

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/** Optional access used to sort recently active apps in the profile editor. */
object UsageStatsAccess {
    fun isEnabled(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
