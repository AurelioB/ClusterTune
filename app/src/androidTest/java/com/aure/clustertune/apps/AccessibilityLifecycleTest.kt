package com.aure.clustertune.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.permissions.AppProfileAccessibilityAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class AccessibilityLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class FaultyWindowsService : AppProfileAccessibilityService() {
        var failReads = false
        var reads = 0
        fun attach(context: Context) { attachBaseContext(context) }
        fun connect() { onServiceConnected() }
        override fun readAccessibilityWindows(): SparseArray<List<AccessibilityWindowInfo>> {
            reads++
            if (failReads) throw SecurityException("injected stale accessibility connection")
            return SparseArray()
        }
    }

    @Test fun transientWindowFailure_recoversWithoutCrashingOrAnotherEvent() {
        val service = FaultyWindowsService()
        try {
            main { service.attach(instrumentation.targetContext); service.failReads = true; service.connect() }
            Thread.sleep(150)
            main {
                assertTrue(service.reads > 0)
                assertFalse(AppProfileAccessibilityAccess.isHealthy.value)
                assertFalse(VisibleAppWindowEvents.snapshots.value.isInteractive)
                service.failReads = false
            }
            Thread.sleep(1200)
            main {
                assertTrue(AppProfileAccessibilityAccess.isHealthy.value)
                assertTrue(VisibleAppWindowEvents.snapshots.value.isInteractive)
            }
        } finally { main { service.onDestroy() } }
    }

    @Test fun unbindRejectsLateWakeAndEvents_thenReconnectRestartsSampling() {
        val service = FaultyWindowsService()
        try {
            main { service.attach(instrumentation.targetContext); service.connect() }
            Thread.sleep(100)
            main {
                assertTrue(field(service, "receiverRegistered") as Boolean)
                assertTrue(field(service, "displayListenerRegistered") as Boolean)
                service.onUnbind(Intent())
                assertFalse(field(service, "receiverRegistered") as Boolean)
                assertFalse(field(service, "displayListenerRegistered") as Boolean)
                val before = service.reads
                (field(service, "receiver") as BroadcastReceiver).onReceive(service, Intent(Intent.ACTION_SCREEN_ON))
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                try { event.packageName = "test.app"; service.onAccessibilityEvent(event) } finally { event.recycle() }
                assertEquals(before, service.reads)
                assertFalse(AppProfileAccessibilityAccess.isConnected.value)
                assertFalse(VisibleAppWindowEvents.snapshots.value.isInteractive)
                service.connect()
            }
            Thread.sleep(150)
            main {
                assertTrue(AppProfileAccessibilityAccess.isConnected.value)
                assertTrue(VisibleAppWindowEvents.snapshots.value.isInteractive)
            }
        } finally { main { service.onDestroy() } }
    }

    @Test fun continuousWindowEvents_cannotPostponeSamplingIndefinitely() {
        val service = FaultyWindowsService()
        try {
            main { service.attach(instrumentation.targetContext); service.connect() }
            repeat(30) {
                main {
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                    try { service.onAccessibilityEvent(event) } finally { event.recycle() }
                }
                Thread.sleep(10)
            }
            main { assertTrue("window stream starved sampling", service.reads >= 2) }
        } finally { main { service.onDestroy() } }
    }

    @Test fun failedCoordinator_isReportedWithoutCrashingOrClaimingHealthyConnection() {
        val service = FaultyWindowsService()
        try {
            main {
                service.attach(instrumentation.targetContext)
                service.connect()
                val coordinator = field(service, "coordinator") as AppProfileCoordinator
                val scope = AppProfileCoordinator::class.java.getDeclaredField("scope")
                    .apply { isAccessible = true }.get(coordinator) as CoroutineScope
                scope.launch { throw IllegalStateException("injected background worker failure") }
            }
            Thread.sleep(1400)
            main {
                assertTrue(AppProfileAccessibilityAccess.isConnected.value)
                assertFalse(AppProfileAccessibilityAccess.isHealthy.value)
                assertNull(field(service, "coordinator"))
            }
        } finally { main { service.onDestroy() } }
    }

    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun field(service: AppProfileAccessibilityService, name: String): Any? =
        AppProfileAccessibilityService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service)
}
