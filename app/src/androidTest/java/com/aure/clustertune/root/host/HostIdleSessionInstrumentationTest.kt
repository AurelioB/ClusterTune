package com.aure.clustertune.root.host

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.apps.PauseAppDetectionRule
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootShellExecutionMethod
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: fixed-profile writes, controlled firmware resets, and exact initial-state cleanup. */
@RunWith(AndroidJUnit4::class)
class HostIdleSessionInstrumentationTest {
    @get:Rule internal val exclusiveHost = PauseAppDetectionRule()

    @Test fun pServerHasNoIdleTimerAndRepairsPermissionsOnlyOnApply() = exercise(PServerExecutionMethod())
    @Test fun rootHasNoIdleTimerAndRepairsPermissionsOnlyOnApply() = exercise(RootShellExecutionMethod())

    private fun exercise(method: PrivilegedExecutionMethod) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("minimumProtection") == "1")
        assumeTrue("${method.id} unavailable", method.probe().isAvailable)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ClusterTuneHostClient(context, PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
        val initial = client.readSnapshot().getOrThrow()
        val cpus = initial.capabilities.cpus
        val minModes = cpus.map { Os.stat(it.minPath).st_mode and 0x1ff }
        val maxModes = cpus.map { Os.stat(it.maxPath).st_mode and 0x1ff }
        try {
            assertNoTimerThread(method)
            // Looking up capabilities/idle status must not start a timer either.
            client.readAutoCapabilities().getOrThrow()
            assertNotEquals(HostAutoSessionStatus.ACTIVE, client.readAutoTelemetry().getOrThrow().status)
            assertNoTimerThread(method)
            assumeTrue("requires writable minimums and lower supported ceilings", cpus.isNotEmpty() &&
                minModes.all { it and 0x92 != 0 } && cpus.all { cpu ->
                    cpu.minimumCandidates.isNotEmpty() && cpu.supportedFrequencies.any { it < cpu.selectableMax }
                })
            val ceilings = cpus.mapIndexed { index, cpu -> initial.state.cpuMax[index].takeIf { it < cpu.selectableMax }
                ?: cpu.supportedFrequencies.filter { it < cpu.selectableMax }.max() }
            val request = ApplyRequest(ceilings, null, false, cpuIds = cpus.map { it.id })
            client.applyProfile(request).getOrThrow()
            assertNoTimerThread(method)
            runPrivileged(method, cpus.flatMapIndexed { index, cpu -> listOf(
                "chmod ${minModes[index].toString(8)} ${quote(cpu.minPath)}",
                "chmod ${(maxModes[index] or 0x80).toString(8)} ${quote(cpu.maxPath)}",
                "echo ${cpu.stockMax} > ${quote(cpu.maxPath)}",
                "chmod ${maxModes[index].toString(8)} ${quote(cpu.maxPath)}",
            ) }.joinToString("\n"))
            Thread.sleep(1_200)
            assertEquals("fixed profiles must not silently reapply after a reset",
                cpus.map { it.stockMax }, client.readSnapshot().getOrThrow().state.cpuMax)
            assertNoTimerThread(method)
            // The original error is fixed at the next intentional apply, without polling.
            client.applyProfile(request).getOrThrow()
            assertEquals(ceilings, client.readSnapshot().getOrThrow().state.cpuMax)
            assertEquals(minModes.map { it and 0x16d }, cpus.map { Os.stat(it.minPath).st_mode and 0x1ff })
            assertNoTimerThread(method)
        } finally {
            client.applyProfile(ApplyRequest(initial.state.cpuMax, null, false,
                cpuIds = cpus.map { it.id }, maximumsOnly = true)).getOrThrow()
            client.stop().getOrThrow()
            assertEquals(minModes, cpus.map { Os.stat(it.minPath).st_mode and 0x1ff })
        }
    }

    private fun assertNoTimerThread(method: PrivilegedExecutionMethod) {
        val names = runPrivileged(method, """
            matches=""
            for proc in /proc/[0-9]*; do
              cmd=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null) || continue
              case "${'$'}cmd" in
                *"com.aure.clustertune.root.host.ClusterTuneHostEntry clustertune.host.${Process.myUid()} ${Process.myUid()} "*) matches="${'$'}matches ${'$'}proc" ;;
              esac
            done
            set -- ${'$'}matches
            [ "${'$'}#" = 1 ]
            for comm in "${'$'}1"/task/*/comm; do cat "${'$'}comm"; done
        """.trimIndent())
        // Linux truncates thread names to 15 characters.
        assertFalse("unexpected host timer threads: $names", names.lineSequence().any {
            it.startsWith("ClusterTune-ses") || it.startsWith("ClusterTune-hos") || it.startsWith("ClusterTune-aut")
        })
    }

    private fun runPrivileged(method: PrivilegedExecutionMethod, commands: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = File(context.cacheDir, "idle-session-test.sh")
        val output = File(context.cacheDir, "idle-session-output.txt")
        output.writeText("")
        script.writeText("set -e\n{\n$commands\n} > ${quote(output.path)}\necho __DONE__ >> ${quote(output.path)}\n")
        try {
            method.launchHost(HostLaunchRequest(context.cacheDir.path, script.path)).getOrThrow()
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            while (!output.readText().contains("__DONE__") && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
            val result = output.readText()
            assertTrue("privileged fixture must complete: $result", result.contains("__DONE__"))
            return result.substringBefore("__DONE__").trim()
        } finally { script.delete(); output.delete() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
