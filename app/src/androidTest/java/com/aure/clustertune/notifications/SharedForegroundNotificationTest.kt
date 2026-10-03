package com.aure.clustertune.notifications

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.overlay.OverlayHostService
import com.aure.clustertune.sleep.SleepProfileMonitorService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android's shared-ID lifetime using the actual background services. */
@RunWith(AndroidJUnit4::class)
class SharedForegroundNotificationTest {
    @Test fun stoppingOverlay_keepsSleepNotification() = checkSharedLifetime(stopOverlayFirst = true)
    @Test fun stoppingSleep_keepsOverlayNotification() = checkSharedLifetime(stopOverlayFirst = false)

    private fun checkSharedLifetime(stopOverlayFirst: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        assumeTrue(NotificationManagerCompat.from(context).areNotificationsEnabled())
        val storage = SettingsStorage(context)
        val original = storage.settings.first()
        val notifications = context.getSystemService(NotificationManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val overlay = OverlayHostService::class.java
        val sleep = SleepProfileMonitorService::class.java
        fun stop(type: Class<*>) { context.stopService(Intent(context, type)) }
        @Suppress("DEPRECATION")
        fun foreground(type: Class<*>) = activityManager.getRunningServices(100)
            .any { it.service.className == type.name && it.foreground }
        fun sharedCount() = notifications.activeNotifications.count { it.id == AppForegroundNotification.ID }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            storage.persistSleepProfile(true, null)
            scenario.onActivity {
                SleepProfileMonitorService.start(it)
                OverlayHostService.previewEdgeHandle(it, 72, 10, 50, 94)
            }
            await("both services must be foreground") { foreground(overlay) && foreground(sleep) }
            await("one shared notification for both services") { sharedCount() == 1 }
            assertTrue("no duplicate persistent notification", notifications.activeNotifications.size == 1)

            val first = if (stopOverlayFirst) overlay else sleep
            val last = if (stopOverlayFirst) sleep else overlay
            stop(first)
            await("first service must stop") { !foreground(first) }
            assertTrue("remaining service must stay foreground", foreground(last))
            // Allow asynchronous notification cancellation to run before checking retention.
            Thread.sleep(500)
            assertTrue("shared notification must survive", sharedCount() == 1)
            stop(last)
            await("last service must remove shared notification") { !foreground(last) && sharedCount() == 0 }
        } finally {
            stop(overlay)
            stop(sleep)
            storage.persistSleepProfile(original.sleepProfileEnabled, original.sleepProfileId)
            scenario.onActivity {
                if (original.sleepProfileEnabled) SleepProfileMonitorService.start(it)
                if (original.leftEdgeProfilePickerEnabled) OverlayHostService.showEdgeHandle(it)
            }
            scenario.close()
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue(message, condition())
    }
}
