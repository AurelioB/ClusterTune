package com.aure.clustertune.apps

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.host.ClusterTuneHostClient
import com.aure.clustertune.AppContainer
import com.aure.clustertune.model.AppProfileAssignment
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDetectionLifecycleTest {
    @Before fun wakeDeviceForInteractiveChecks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
    }

    @Test fun detectedAppAppliesItsAssignmentAndLeavingRestoresTheNormalProfile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = productionResolver()
        assumeTrue("privileged execution unavailable", resolver.isAvailable)
        val container = AppContainer(context)
        val storage = container.profileStorage
        val target = "com.android.settings"
        val previous = storage.appProfileAssignments.first().firstOrNull { it.packageName == target }
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val previousEffective = storage.effectiveProfileState.first()
        try {
            scenario.onActivity { AppDetectionService.start(it, retry = true) }
            await { AppDetectionState.status.value.available }
            val baseline = ClusterTuneHostClient(context, resolver).readSnapshot().getOrThrow()
            // Use the existing ceilings: this exercises the real profile writer without
            // introducing a new performance level on the user's hardware.
            val values = baseline.capabilities.cpus.mapIndexed { index, domain ->
                domain.id.removePrefix("policy").toInt() to baseline.state.cpuMax[index].toInt()
            }.toMap()
            storage.saveAppProfileAssignment(AppProfileAssignment(target, "Settings", customMaxFrequencies = values,
                customGpuMaxFrequencyHz = baseline.state.gpuMax?.toInt()))
            scenario.onActivity { it.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
            await { runBlocking { target in storage.effectiveProfileState.first()?.contributingPackageNames.orEmpty() } }
            scenario.onActivity { it.startActivity(Intent(it, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await { runBlocking { target !in storage.effectiveProfileState.first()?.contributingPackageNames.orEmpty() } }
        } finally {
            if (previous != null) storage.saveAppProfileAssignment(previous) else storage.deleteAppProfileAssignment(target)
            context.stopService(Intent(context, AppDetectionService::class.java))
            await { !AppDetectionState.status.value.available }
            if (previousEffective != null) storage.persistEffectiveProfileState(previousEffective)
            else storage.clearEffectiveProfileState()
            scenario.close()
            container.close()
        }
    }

    @Test fun appDoesNotDeclareAnAccessibilityService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val services = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services
        assertFalse(services.any { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
        assertTrue(services.any { it.name == AppDetectionService::class.java.name && !it.exported })
    }

    @Test fun foregroundServiceReconnectsAfterPrivilegedHostStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = productionResolver()
        assumeTrue("privileged execution unavailable", resolver.isAvailable)
        val client = ClusterTuneHostClient(context, resolver)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { AppDetectionService.start(it, retry = true) }
            await { AppDetectionState.status.value.available }
            repeat(3) {
                val before = client.readSnapshot().getOrThrow().epoch
                client.stop().getOrThrow()
                await { !AppDetectionState.status.value.available }
                await { AppDetectionState.status.value.available }
                assertNotEquals(before, client.readSnapshot().getOrThrow().epoch)
            }
            assertNull(AppDetectionState.status.value.error)
        } finally {
            context.stopService(Intent(context, AppDetectionService::class.java))
            scenario.close()
        }
    }

    @Test fun screenOffClearsAppsAndWakeObtainsAFreshObservation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("privileged execution unavailable", productionResolver().isAvailable)
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { it.readBytes() }
        }
        try {
            scenario.onActivity { AppDetectionService.start(it, retry = true) }
            await { AppDetectionState.status.value.available }
            shell("input keyevent KEYCODE_HOME")
            await { VisibleAppWindowEvents.snapshots.value.packages.isNotEmpty() }
            shell("input keyevent KEYCODE_SLEEP")
            await { !VisibleAppWindowEvents.snapshots.value.isInteractive }
            assertTrue(VisibleAppWindowEvents.snapshots.value.packages.isEmpty())
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            await { VisibleAppWindowEvents.snapshots.value.isInteractive &&
                VisibleAppWindowEvents.snapshots.value.packages.isNotEmpty() }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            context.stopService(Intent(context, AppDetectionService::class.java))
            scenario.close()
        }
    }

    private fun productionResolver(): PrivilegedExecutionResolver {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return PrivilegedExecutionResolver.default(context).also { resolver ->
            resolver.setConfiguredMethodId(runBlocking {
                com.aure.clustertune.data.SettingsStorage(context).settings.first().privilegedExecutionMethodId
            })
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
        while (!condition() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue("App detection did not recover: ${AppDetectionState.status.value}", condition())
    }
}
