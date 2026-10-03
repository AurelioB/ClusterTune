package com.aure.clustertune.data

import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.overlay.OverlayHostService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentalHudSettingTest {
    @Test fun disablingExperimentalFeaturesClosesHudAndRejectsDirectLaunch() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Settings.canDrawOverlays(context))
        val storage = SettingsStorage(context)
        val previous = storage.settings.first().autoTuneEnabled
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        fun hudVisible(): Boolean {
            var visible = false
            instrumentation.runOnMainSync {
                visible = WindowInspector.getGlobalWindowViews().any {
                    (it.layoutParams as? WindowManager.LayoutParams)?.title == "ClusterTune performance HUD"
                }
            }
            return visible
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.uptimeMillis() + 10_000
            while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            assertTrue(message, condition())
        }
        try {
            storage.persistAutoTuneEnabled(true)
            scenario.onActivity { OverlayHostService.togglePerformanceHud(it) }
            await("enabled setting allows the HUD", ::hudVisible)
            storage.persistAutoTuneEnabled(false)
            await("disabling must remove the HUD window") { !hudVisible() }
            scenario.onActivity { OverlayHostService.togglePerformanceHud(it) }
            val deadline = android.os.SystemClock.uptimeMillis() + 1_000
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                assertTrue("disabled direct launch must not attach a HUD", !hudVisible())
                Thread.sleep(50)
            }
        } finally {
            context.stopService(Intent(context, OverlayHostService::class.java))
            storage.persistAutoTuneEnabled(previous)
            scenario.close()
        }
    }
}
