package com.aure.clustertune.root.host

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.AppContainer
import com.aure.clustertune.MainActivity
import com.aure.clustertune.apps.AppDetectionService
import com.aure.clustertune.apps.AppDetectionState
import com.aure.clustertune.apps.VisibleAppWindowEvents
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootShellExecutionMethod
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.sleep.SleepProfileMonitorService
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Physical opt-in matrix. All saved settings, assignments, history, and clock ceilings are restored. */
@RunWith(AndroidJUnit4::class)
class ProfileResetDeviceMatrixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container by lazy { AppContainer(context) }
    private val storage get() = container.profileStorage
    private val settings get() = container.settingsStorage
    private val repository get() = container.repository
    private var client: ClusterTuneHostClient? = null
    private val report = JSONObject().put("cases", JSONArray())
    private val reportFile by lazy { File(context.filesDir, "profile-reset-matrix.json") }
    private var currentCase: JSONObject? = null
    private var main: ActivityScenario<MainActivity>? = null
    private var component: ActivityScenario<ComponentActivity>? = null
    private val failures = mutableListOf<String>()

    /** The external ADB harness kills the app only after this fixture writes its ready marker. */
    @Test fun holdForExternalClientDeathCheck() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileResetHold") == "1")
        assumeTrue("this fixture requires Auto Tune off", !settings.settings.first().autoTuneEnabled)
        wake()
        container.privilegedExecutionResolver.setConfiguredMethodId(settings.settings.first().privilegedExecutionMethodId)
        val host = container.hostClient
        val snapshot = host.readSnapshot().getOrThrow()
        assumeTrue("requires the user's existing CPU underclock", snapshot.capabilities.cpus.any {
            snapshot.state.cpuMax[snapshot.capabilities.cpus.indexOf(it)] < it.selectableMax
        })
        try {
            host.applyProfile(ApplyRequest(snapshot.state.cpuMax, null, false,
                cpuIds = snapshot.capabilities.cpus.map { it.id })).getOrThrow()
            File(context.filesDir, "profile-reset-client-death-ready.json").writeText(
                stateJson(host.readSnapshot().getOrThrow()).put("method", host.selectedMethodId).toString(2))
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nClient-death fixture ready\n") })
            // This is test-only waiting, bounded in case the external harness never arrives.
            val deadline = SystemClock.uptimeMillis() + 120_000
            while (SystemClock.uptimeMillis() < deadline) Thread.sleep(250)
        } finally { host.stop(); container.close() }
    }

    @Test fun autoTuneOffResetMatrix() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("profileResetMatrix") == "1")
        val pserver = PServerExecutionMethod()
        assumeTrue("PServer unavailable", pserver.probe().isAvailable)
        val originalSettings = settings.settings.first()
        val originalAssignments = storage.appProfileAssignments.first()
        val originalHistory = storage.profileSwitchHistory.first()
        val originalEffective = storage.effectiveProfileState.first()
        val originalValues = storage.lastValues.first()
        val originalGpu = storage.lastGpuValue.first()
        val originalId = storage.lastAppliedDisplayProfileId.first()
        val originalSelected = storage.selectedProfileId.first()
        val originalSleepValues = storage.sleepRestoreValues.first()
        val originalSleepGpu = storage.sleepRestoreGpuValue.first()
        val originalSleepGpuKnown = storage.sleepRestoreGpuKnown.first()
        val originalSleepId = storage.sleepRestoreDisplayProfileId.first()
        val originalSleepEffective = storage.sleepRestoreEffectiveProfileState.first()
        val detectionWasRunning = running(AppDetectionService::class.java.name)
        val sleepWasRunning = running(SleepProfileMonitorService::class.java.name)
        wake()
        stopAutomation()
        container.privilegedExecutionResolver.setConfiguredMethodId(pserver.id)
        client = container.hostClient
        val initial = client!!.readSnapshot().getOrThrow()
        report.put("startedAtMs", System.currentTimeMillis()).put("autoTuneEnabledDuringTests", false)
            .put("initial", stateJson(initial)).put("originalSettings", JSONObject()
                .put("autoTuneEnabled", originalSettings.autoTuneEnabled)
                .put("sleepProfileEnabled", originalSettings.sleepProfileEnabled)
                .put("sleepProfileId", originalSettings.sleepProfileId)
                .put("assignmentCount", originalAssignments.size))
        try {
            settings.persistAutoTuneEnabled(false)
            settings.persistSleepProfile(false, originalSettings.sleepProfileId)
            settings.persistProfileSwitchToastsEnabled(false)
            settings.persistPrivilegedExecutionMethodId(pserver.id)
            originalAssignments.forEach { storage.deleteAppProfileAssignment(it.packageName) }
            storage.clearSleepRestoreState()
            val state = repository.observeState().first()
            val large = state.displayProfiles.first { it.name == "Large Underclock" }
            val stock = state.displayProfiles.first { it.id == ProfileStateResolver.STOCK_PROFILE_ID }

            scenario("pserver_fixed_foreground_60s") {
                applyNormal(large)
                main = ActivityScenario.launch(MainActivity::class.java)
                main!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                await { AppDetectionState.status.value.available }
                hold(large, 60)
            }
            scenario("pserver_fixed_background_90s") {
                applyNormal(large)
                shell("input keyevent KEYCODE_HOME")
                hold(large, 90)
            }
            scenario("sleep_automation_off_three_screen_cycles") {
                applyNormal(large)
                repeat(3) { cycle ->
                    shell("input keyevent KEYCODE_SLEEP")
                    hold(large, 3, "asleep-$cycle")
                    wake()
                    hold(large, 8, "awake-$cycle")
                }
            }
            scenario("stock_baseline_app_underclock_restores_stock_on_exit") {
                appAssignment(stock, large)
            }
            scenario("underclock_baseline_app_stock_restores_underclock_on_exit") {
                appAssignment(large, stock)
            }
            scenario("stock_baseline_sleep_underclock_restores_stock_on_wake") {
                sleepTransition(stock, large)
            }
            scenario("underclock_baseline_sleep_stock_restores_underclock_on_wake") {
                sleepTransition(large, stock)
            }
            // Direct host cases exclude app/sleep automation so its own clients cannot replace a forced host.
            stopAutomation()
            main?.close(); main = null
            scenario("invalid_apply_preserves_previous_underclock") {
                applyNormal(large)
                val before = sample("before-invalid")
                val invalid = request(large).copy(cpuMax = listOf(-1L) + request(large).cpuMax.drop(1))
                val failure = client!!.applyProfile(invalid).exceptionOrNull()
                assertNotNull("malformed request must fail", failure)
                currentCase!!.put("expectedError", failure!!.message)
                assertProfile(large, client!!.readSnapshot().getOrThrow())
                assertEquals(before.getJSONArray("cpuMax").toString(), sample("after-invalid").getJSONArray("cpuMax").toString())
            }
            scenario("foreign_minimum_permissions_fail_without_selecting_stock") {
                applyNormal(large)
                val cpu = initial.capabilities.cpus.first()
                val protectedMode = Os.stat(cpu.minPath).st_mode and 0x1ff
                try {
                    privileged(pserver, "chmod 644 ${quote(cpu.minPath)}")
                    val failure = client!!.applyProfile(request(large)).exceptionOrNull()
                    assertNotNull("unfamiliar permissions must be rejected", failure)
                    currentCase!!.put("expectedError", failure!!.message)
                    sample("after-rejected-apply")
                    assertProfile(large, client!!.readSnapshot().getOrThrow())
                } finally { privileged(pserver, "chmod ${protectedMode.toString(8)} ${quote(cpu.minPath)}") }
                client!!.applyProfile(request(large)).getOrThrow()
            }
            scenario("graceful_host_stop_and_reconnect") {
                applyNormal(large)
                val before = client!!.readSnapshot().getOrThrow().epoch
                client!!.stop().getOrThrow()
                Thread.sleep(1_000)
                val after = client!!.readSnapshot().getOrThrow()
                assertNotEquals(before, after.epoch)
                assertProfile(large, after)
                hold(large, 12)
                client!!.applyProfile(request(large)).getOrThrow()
            }
            scenario("privileged_host_kill_and_reconnect") {
                applyNormal(large)
                val before = client!!.readSnapshot().getOrThrow().epoch
                privileged(pserver, ownedHostSelection() + "\nkill -9 \"\${1##*/}\"")
                Thread.sleep(500)
                val after = client!!.readSnapshot().getOrThrow()
                assertNotEquals(before, after.epoch)
                assertProfile(large, after)
                hold(large, 12)
                client!!.applyProfile(request(large)).getOrThrow()
            }
            val root = RootShellExecutionMethod()
            if (root.probe().isAvailable) {
                client!!.stop().getOrThrow()
                Thread.sleep(500)
                client = ClusterTuneHostClient(context, PrivilegedExecutionResolver(listOf(root), listOf(root.id)))
                component = ActivityScenario.launch(ComponentActivity::class.java)
                component!!.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                scenario("root_fixed_foreground_45s") {
                    client!!.applyProfile(request(large)).getOrThrow()
                    hold(large, 45)
                }
                scenario("root_fixed_background_60s") {
                    shell("input keyevent KEYCODE_HOME")
                    hold(large, 60)
                }
            } else report.put("rootSkipped", "app-facing root unavailable")
        } finally {
            wake()
            stopAutomation()
            main?.close(); component?.close()
            storage.appProfileAssignments.first().forEach { storage.deleteAppProfileAssignment(it.packageName) }
            originalAssignments.forEach { storage.saveAppProfileAssignment(it) }
            storage.persistNormalProfileState(originalValues, originalId, originalGpu, originalSelected)
            if (originalEffective == null) storage.clearEffectiveProfileState() else storage.persistEffectiveProfileState(originalEffective)
            storage.clearSleepRestoreState()
            if (originalSleepValues.isNotEmpty()) storage.persistSleepRestoreState(originalSleepValues, originalSleepId,
                originalSleepGpu, originalSleepGpuKnown, originalSleepEffective)
            storage.clearProfileSwitchHistory()
            originalHistory.asReversed().forEach { storage.appendProfileSwitchHistory(it, maxOf(originalHistory.size, originalSettings.profileSwitchHistoryLimit)) }
            settings.persistAutoTuneEnabled(originalSettings.autoTuneEnabled)
            settings.persistSleepProfile(originalSettings.sleepProfileEnabled, originalSettings.sleepProfileId)
            settings.persistProfileSwitchToastsEnabled(originalSettings.profileSwitchToastsEnabled)
            settings.persistPrivilegedExecutionMethodId(originalSettings.privilegedExecutionMethodId)
            // Restore ceilings exactly; restore protection through the ordinary full apply when it was owned.
            val cpus = initial.capabilities.cpus
            client!!.applyProfile(ApplyRequest(initial.state.cpuMax, initial.state.gpuMax, false,
                cpuIds = cpus.map { it.id }, gpuId = initial.capabilities.gpu?.id,
                gpuMaxPath = initial.capabilities.gpu?.maxPath)).getOrThrow()
            report.put("restored", stateJson(client!!.readSnapshot().getOrThrow())).put("finishedAtMs", System.currentTimeMillis())
            client!!.stop().getOrThrow()
            if (detectionWasRunning) AppDetectionService.start(context)
            if (sleepWasRunning) SleepProfileMonitorService.start(context)
            container.close()
            save()
        }
        assertTrue("matrix failures: ${failures.joinToString()}; see ${reportFile.name}", failures.isEmpty())
    }

    private suspend fun scenario(name: String, block: suspend () -> Unit) {
        val current = JSONObject().put("name", name).put("startedAtMs", System.currentTimeMillis()).put("samples", JSONArray())
        currentCase = current
        report.getJSONArray("cases").put(current)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nMatrix: $name\n") })
        save()
        try {
            assertFalse(settings.settings.first().autoTuneEnabled)
            block()
            current.put("status", "passed")
        } catch (failure: Throwable) {
            failures += name
            current.put("status", "failed").put("error", failure.toString())
        } finally {
            wake()
            current.put("finishedAtMs", System.currentTimeMillis())
            current.put("history", JSONArray(storage.profileSwitchHistory.first().filter {
                it.timestampMillis >= current.getLong("startedAtMs")
            }.map { JSONObject().put("timeMs", it.timestampMillis).put("profile", it.profileName).put("trigger", it.trigger) }))
            save()
        }
    }

    private suspend fun applyNormal(profile: PerformanceProfile) {
        val state = repository.observeState().first()
        repository.applyValues(state.policies, profile.maxFrequencies,
            isReset = profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = profile.id, gpuPolicy = state.gpuPolicy,
            selectedGpuMaxFrequencyHz = profile.gpuMaxFrequencyHz).getOrThrow()
        repository.logProfileSwitch(profile.id, profile.name, "Device reset matrix: baseline")
        sample("applied-${profile.name}")
    }

    private fun request(profile: PerformanceProfile): ApplyRequest {
        val caps = client!!.readSnapshot().getOrThrow().capabilities
        return ApplyRequest(caps.cpus.map { profile.maxFrequencies.getValue(it.id.removePrefix("policy").toInt()).toLong() },
            profile.gpuMaxFrequencyHz?.toLong(), profile.id == ProfileStateResolver.STOCK_PROFILE_ID,
            cpuIds = caps.cpus.map { it.id }, gpuId = caps.gpu?.id, gpuMaxPath = caps.gpu?.maxPath)
    }

    private fun assertProfile(profile: PerformanceProfile, snapshot: HostSnapshot) {
        val caps = snapshot.capabilities
        val values = caps.cpus.map { profile.maxFrequencies.getValue(it.id.removePrefix("policy").toInt()).toLong() }
        val targets = resolveHostMaximumTargets(caps, ApplyRequest(values, profile.gpuMaxFrequencyHz?.toLong(),
            profile.id == ProfileStateResolver.STOCK_PROFILE_ID))
        snapshot.state.cpuMax.forEachIndexed { index, actual ->
            assertTrue("${profile.name}: ${caps.cpus[index].id} expected ${targets.cpuAcceptedCeilings[index]}, actual $actual",
                actual in targets.cpuAcceptedCeilings[index])
        }
        if (targets.gpuExpected != null) assertTrue("GPU expected ${targets.gpuAcceptedCeilings}, actual ${snapshot.state.gpuMax}",
            snapshot.state.gpuMax in targets.gpuAcceptedCeilings)
    }

    private suspend fun hold(profile: PerformanceProfile, seconds: Int, label: String = "hold") {
        var drift: String? = null
        repeat(seconds) {
            val snapshot = client!!.readSnapshot().getOrThrow()
            sample("$label-$it", snapshot)
            runCatching { assertProfile(profile, snapshot) }.exceptionOrNull()?.let { error -> drift = error.message }
            Thread.sleep(1_000)
        }
        assertNull(drift)
    }

    private suspend fun appAssignment(normal: PerformanceProfile, assigned: PerformanceProfile) {
        stopAutomation()
        applyNormal(normal)
        storage.saveAppProfileAssignment(AppProfileAssignment("com.android.settings", "Settings", profileId = assigned.id))
        AppDetectionService.start(context, retry = true)
        try {
            await { AppDetectionState.status.value.available }
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { "com.android.settings" in storage.effectiveProfileState.first()?.contributingPackageNames.orEmpty() }
            assertProfile(assigned, client!!.readSnapshot().getOrThrow())
            hold(assigned, 3, "assigned-app")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await { "com.android.settings" !in storage.effectiveProfileState.first()?.contributingPackageNames.orEmpty() }
            assertProfile(normal, client!!.readSnapshot().getOrThrow())
            hold(normal, 3, "after-app-exit")
        } finally {
            context.stopService(Intent(context, AppDetectionService::class.java))
            await { !running(AppDetectionService::class.java.name) }
            storage.deleteAppProfileAssignment("com.android.settings")
        }
    }

    private suspend fun sleepTransition(normal: PerformanceProfile, asleep: PerformanceProfile) {
        stopAutomation()
        applyNormal(normal)
        settings.persistSleepProfile(true, asleep.id)
        storage.clearSleepRestoreState()
        SleepProfileMonitorService.start(context)
        await { running(SleepProfileMonitorService::class.java.name) }
        try {
            Thread.sleep(500)
            shell("input keyevent KEYCODE_SLEEP")
            await { storage.effectiveProfileState.first()?.source == com.aure.clustertune.model.EffectiveProfileSource.SLEEP }
            assertProfile(asleep, client!!.readSnapshot().getOrThrow())
            hold(asleep, 4, "sleep-profile")
            wake()
            await { storage.sleepRestoreValues.first().isEmpty() }
            assertProfile(normal, client!!.readSnapshot().getOrThrow())
            hold(normal, 5, "wake-restore")
        } finally {
            wake()
            context.stopService(Intent(context, SleepProfileMonitorService::class.java))
            await { !running(SleepProfileMonitorService::class.java.name) }
            settings.persistSleepProfileEnabled(false)
            storage.clearSleepRestoreState()
        }
    }

    private suspend fun sample(label: String, snapshot: HostSnapshot = client!!.readSnapshot().getOrThrow()): JSONObject {
        val sample = stateJson(snapshot).put("label", label).put("timeMs", System.currentTimeMillis())
            .put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
            .put("method", client!!.selectedMethodId)
            .put("visibleApps", JSONArray(VisibleAppWindowEvents.snapshots.value.packages.toList()))
            .put("effectiveProfile", storage.effectiveProfileState.first()?.name)
        currentCase?.getJSONArray("samples")?.put(sample)
        save()
        return sample
    }

    private fun stateJson(snapshot: HostSnapshot) = JSONObject()
        .put("epoch", snapshot.epoch).put("cpuMax", JSONArray(snapshot.state.cpuMax))
        .put("cpuMin", JSONArray(snapshot.state.cpuMin)).put("gpuMax", snapshot.state.gpuMax)
        .put("minimumModes", JSONArray(snapshot.capabilities.cpus.map { (Os.stat(it.minPath).st_mode and 0x1ff).toString(8) }))

    private suspend fun stopAutomation() {
        context.stopService(Intent(context, AppDetectionService::class.java))
        context.stopService(Intent(context, SleepProfileMonitorService::class.java))
        await { !running(AppDetectionService::class.java.name) && !running(SleepProfileMonitorService::class.java.name) }
    }

    @Suppress("DEPRECATION")
    private fun running(name: String) = context.getSystemService(ActivityManager::class.java).getRunningServices(100).any { it.service.className == name }

    private suspend fun await(condition: suspend () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        assertTrue("condition timed out; detector=${AppDetectionState.status.value}", condition())
    }

    private fun privileged(method: PrivilegedExecutionMethod, commands: String) {
        val script = File(context.cacheDir, "reset-matrix-command.sh")
        val result = File(context.cacheDir, "reset-matrix-result.txt")
        result.writeText("")
        script.writeText("set -e\n$commands\necho done > ${quote(result.path)}\n")
        try {
            method.launchHost(HostLaunchRequest(context.cacheDir.path, script.path)).getOrThrow()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (result.readText().trim() != "done" && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            assertEquals("privileged fixture must complete", "done", result.readText().trim())
        } finally { script.delete(); result.delete() }
    }

    private fun ownedHostSelection() = """
        matches=""
        for proc in /proc/[0-9]*; do
          cmd=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null) || continue
          case "${'$'}cmd" in
            *"com.aure.clustertune.root.host.ClusterTuneHostEntry clustertune.host.${Process.myUid()} ${Process.myUid()} "*) matches="${'$'}matches ${'$'}proc" ;;
          esac
        done
        set -- ${'$'}matches
        [ "${'$'}#" = 1 ]
    """.trimIndent()

    private fun wake() { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
    private fun shell(command: String) { hostTestShell(command) }
    private fun save() {
        val temporary = File(reportFile.parentFile, "profile-reset-matrix.tmp")
        temporary.writeText(report.toString(2))
        check(temporary.renameTo(reportFile)) { "cannot save matrix observations" }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
