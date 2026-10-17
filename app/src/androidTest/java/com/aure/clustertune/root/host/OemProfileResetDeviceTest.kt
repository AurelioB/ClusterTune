package com.aure.clustertune.root.host

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.AppContainer
import com.aure.clustertune.apps.PauseAppDetectionRule
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.RootShellExecutionMethod
import com.aure.clustertune.sleep.SleepProfileMonitorService
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Thor-only physical diagnostic, using the three documented firmware modes; original mode is restored. */
@RunWith(AndroidJUnit4::class)
class OemProfileResetDeviceTest {
    @get:Rule internal val exclusiveHost = PauseAppDetectionRule()

    @Test fun observeModeChangesWithAndWithoutMinimumProtection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("oemResetMatrix") == "1")
        assumeTrue(android.os.Build.MODEL.replace(' ', '_') == "AYN_Thor")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = AppContainer(context)
        assumeTrue("Auto Tune must be off", !container.settingsStorage.settings.first().autoTuneEnabled)
        val originalMode = hostTestShell("getprop persist.vendor.debug.mode").trim()
        assumeTrue("documented Thor mode required", originalMode in listOf("0", "1", "2"))
        @Suppress("DEPRECATION")
        val sleepWasRunning = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == SleepProfileMonitorService::class.java.name }
        context.stopService(Intent(context, SleepProfileMonitorService::class.java))
        val method = PServerExecutionMethod()
        assumeTrue(method.probe().isAvailable)
        // The app still uses PServer; Magisk root only operates the device's firmware-mode control.
        val commandMethod = RootShellExecutionMethod()
        assumeTrue(commandMethod.probe().isAvailable)
        val client = ClusterTuneHostClient(context,
            com.aure.clustertune.root.PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
        val initial = client.readSnapshot().getOrThrow()
        assumeTrue("requires existing underclock", initial.capabilities.cpus.any {
            initial.state.cpuMax[initial.capabilities.cpus.indexOf(it)] < it.selectableMax
        })
        val cpus = initial.capabilities.cpus
        val fixed = ApplyRequest(initial.state.cpuMax, null, false, cpuIds = cpus.map { it.id })
        val cases = JSONArray()
        val report = JSONObject().put("originalMode", originalMode).put("autoTuneEnabled", false)
            .put("initialCpuMax", JSONArray(initial.state.cpuMax)).put("cases", cases)
        val reportFile = File(context.filesDir, "oem-profile-reset-matrix.json")
        fun save() { reportFile.writeText(report.toString(2)) }
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
        fun privileged(commands: String): String {
            val script = File(context.cacheDir, "oem-reset-command.sh")
            val output = File(context.cacheDir, "oem-reset-output.txt")
            output.writeText("")
            script.writeText("set -e\n{\n$commands\n} > ${quote(output.path)}\necho __DONE__ >> ${quote(output.path)}\n")
            try {
                commandMethod.launchHost(HostLaunchRequest(context.cacheDir.path, script.path)).getOrThrow()
                val deadline = SystemClock.uptimeMillis() + 5_000
                while (!output.readText().contains("__DONE__") && SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
                val result = output.readText()
                assertTrue("privileged fixture must complete: $result", result.contains("__DONE__"))
                return result.substringBefore("__DONE__").trim()
            } finally { script.delete(); output.delete() }
        }
        fun mode(value: String) {
            privileged("setprop persist.vendor.debug.mode $value")
            assertEquals(value, hostTestShell("getprop persist.vendor.debug.mode").trim())
        }
        fun capture(label: String, seconds: Int, throughHost: Boolean) {
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "\nOEM matrix: $label\n") })
            val samples = JSONArray()
            val entry = JSONObject().put("name", label).put("samples", samples)
            cases.put(entry)
            repeat(seconds) {
                val sample = JSONObject().put("timeMs", System.currentTimeMillis())
                    .put("mode", hostTestShell("getprop persist.vendor.debug.mode").trim())
                if (throughHost) {
                    val state = client.readSnapshot().getOrThrow().state
                    sample.put("cpuMax", JSONArray(state.cpuMax)).put("cpuMin", JSONArray(state.cpuMin))
                        .put("minimumModes", JSONArray(cpus.map { (Os.stat(it.minPath).st_mode and 0x1ff).toString(8) }))
                } else {
                    // Do not reconnect a host while measuring the effect of releasing ownership.
                    val raw = privileged(cpus.joinToString("\n") { cpu ->
                        "echo ${cpu.id} \"\$(cat ${quote(cpu.maxPath)})\" \"\$(cat ${quote(cpu.minPath)})\" \"\$(stat -c %a ${quote(cpu.minPath)})\""
                    })
                    val rows = raw.lines().map { it.trim().split(Regex("\\s+")) }
                    assertEquals(cpus.size, rows.size)
                    sample.put("cpuMax", JSONArray(rows.map { it[1].toLong() }))
                        .put("cpuMin", JSONArray(rows.map { it[2].toLong() }))
                        .put("minimumModes", JSONArray(rows.map { it[3] }))
                }
                samples.put(sample)
                save()
                Thread.sleep(1_000)
            }
            entry.put("stockObserved", (0 until samples.length()).any { index ->
                val values = samples.getJSONObject(index).getJSONArray("cpuMax")
                cpus.indices.all { values.getLong(it) == cpus[it].stockMax }
            })
            save()
        }
        try {
            client.applyProfile(fixed).getOrThrow()
            mode("1")
            capture("mode_1_with_protected_underclock", 12, true)
            client.applyProfile(fixed).getOrThrow()
            mode("2")
            capture("mode_2_with_protected_underclock", 12, true)
            client.stop().getOrThrow()
            capture("mode_2_after_host_stop_releases_minimum_permissions", 15, false)
        } finally {
            mode(originalMode)
            client.ensureStarted(5_000).getOrThrow()
            client.applyProfile(fixed.copy(gpuMax = initial.state.gpuMax,
                gpuId = initial.capabilities.gpu?.id, gpuMaxPath = initial.capabilities.gpu?.maxPath)).getOrThrow()
            report.put("restoredMode", hostTestShell("getprop persist.vendor.debug.mode").trim())
                .put("restoredCpuMax", JSONArray(client.readSnapshot().getOrThrow().state.cpuMax))
            save()
            client.stop().getOrThrow()
            if (sleepWasRunning) SleepProfileMonitorService.start(context)
            container.close()
        }
    }
}
