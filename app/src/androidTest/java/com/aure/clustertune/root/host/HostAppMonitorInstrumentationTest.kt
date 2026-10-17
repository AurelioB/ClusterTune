package com.aure.clustertune.root.host

import androidx.activity.ComponentActivity
import android.app.ActivityOptions
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootShellExecutionMethod
import com.aure.clustertune.apps.PauseAppDetectionRule
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HostAppMonitorInstrumentationTest {
    @get:Rule internal val exclusiveHost = PauseAppDetectionRule()

    @Test fun taskNotificationsFollowAppExitWithoutAccessibility() {
        assertNotifications(resolver())
    }

    @Test fun realRootShellReceivesTaskNotifications() {
        val method = RootShellExecutionMethod()
        assumeTrue("app-facing root shell unavailable", method.probe().isAvailable)
        assertNotifications(PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
    }

    private fun assertNotifications(resolver: PrivilegedExecutionResolver) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        assumeTrue("privileged execution unavailable", resolver.autoDetectBestMethod() != null)
        val client = ClusterTuneHostClient(context, resolver)
        val observations = LinkedBlockingQueue<HostAppObservation>()
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val subscription = client.observeAppWindows(observations::offer) {
            observations.offer(HostAppObservation(emptyList(), "host disconnected"))
        }.getOrThrow()
        try {
            await(observations) { it.windows.any { window -> window.packageName == context.packageName } }
            shell("input keyevent KEYCODE_HOME")
            await(observations) {
                it.windows.isNotEmpty() && it.windows.none { window -> window.packageName == context.packageName }
            }
        } finally {
            subscription.close()
            scenario.close()
        }
    }

    @Test fun visibleAppsOnBothPhysicalDisplaysAreReported() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val displays = context.getSystemService(DisplayManager::class.java).displays
            .filter { it.state == Display.STATE_ON }
        assumeTrue("requires two awake displays", displays.size >= 2)
        val resolver = resolver()
        assumeTrue("privileged execution unavailable", resolver.autoDetectBestMethod() != null)
        val client = ClusterTuneHostClient(context, resolver)
        val observations = LinkedBlockingQueue<HostAppObservation>()
        val scenarios = displays.take(2).map { display ->
            ActivityScenario.launch<ComponentActivity>(
                Intent(context, ComponentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle(),
            )
        }
        val subscription = client.observeAppWindows(observations::offer) {}.getOrThrow()
        try {
            val expected = displays.take(2).map { it.displayId }.toSet()
            await(observations) { observation ->
                assertTrue("only the globally focused display may own Auto Tune",
                    observation.windows.filter { it.focused }.map { it.displayId }.toSet().size <= 1)
                observation.windows.filter { it.packageName == context.packageName }
                    .map { it.displayId }.toSet().containsAll(expected)
            }
        } finally {
            subscription.close()
            scenarios.reversed().forEach { it.close() }
        }
    }

    private fun resolver(): PrivilegedExecutionResolver = privilegedHostTestResolver()

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }

    private fun await(queue: LinkedBlockingQueue<HostAppObservation>, matches: (HostAppObservation) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var latest: HostAppObservation? = null
        while (System.nanoTime() < deadline) {
            val observation = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
            latest = observation
            assertTrue("app detection failed: ${observation.error}", observation.error == null)
            if (matches(observation)) return
        }
        error("No matching app observation; last=$latest")
    }
}
