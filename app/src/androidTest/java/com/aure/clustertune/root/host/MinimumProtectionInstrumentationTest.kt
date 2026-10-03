package com.aure.clustertune.root.host

import android.os.Process
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootExec
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: changes live CPU settings and kills only this app's privileged host. */
@RunWith(AndroidJUnit4::class)
class MinimumProtectionInstrumentationTest {
    @Test fun protectedProfiles_releaseOnStockStopAndRecoverAfterHostKill() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("minimumProtection") == "1")
        val digest = MessageDigest.getInstance("SHA-256").digest(File("/system/bin/pservice").readBytes())
            .joinToString("") { "%02x".format(it) }
        assumeTrue(digest == "8a0b75b44f0139843f2608f1ac7946ed1184cb126ed2777ee2bc2fb509357be4")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val method = PServerExecutionMethod()
        assumeTrue(method.probe().isAvailable)
        val resolver = PrivilegedExecutionResolver(listOf(method), listOf(method.id))
        val client = ClusterTuneHostClient(context, resolver)
        client.ensureStarted(5_000).getOrThrow()
        val initial = client.readSnapshot().getOrThrow()
        val cpus = initial.capabilities.cpus
        fun modes() = cpus.map { Os.stat(it.minPath).st_mode and 0x1ff }
        val originalModes = modes()
        assumeTrue("minimums already owned externally", originalModes.all { it and 0x92 != 0 })
        val ceilings = cpus.mapIndexed { index, cpu ->
            initial.state.cpuMax[index].takeIf { it < cpu.selectableMax }
                ?: cpu.supportedFrequencies.filter { it < cpu.selectableMax }.max()
        }
        fun request(values: List<Long>, stock: Boolean = false, maxOnly: Boolean = false) =
            ApplyRequest(values, null, stock, cpuIds = cpus.map { it.id }, maximumsOnly = maxOnly)
        val stock = request(cpus.map { it.stockMax }, stock = true)
        val report = File(context.filesDir, "minimum-protection-report.txt")
        report.writeText("initial=${initial.state}; modes=$originalModes\n")
        try {
            client.applyProfile(request(ceilings)).getOrThrow()
            assertEquals(originalModes.map { it and 0x16d }, modes())
            // Span multiple OEM timer passes. Independent QoS boosts are permitted; modes and
            // ceilings must hold. Record actual minimums for interpreting the physical run.
            repeat(24) {
                Thread.sleep(1_000)
                val state = client.readSnapshot().getOrThrow().state
                assertEquals(ceilings, state.cpuMax)
                assertEquals(originalModes.map { it and 0x16d }, modes())
                report.appendText("protected $it: $state\n")
            }
            client.applyProfile(stock).getOrThrow()
            assertEquals(originalModes, modes())
            Thread.sleep(12_000)
            report.appendText("stock=${client.readSnapshot().getOrThrow().state}; modes=${modes()}\n")
            client.applyProfile(request(ceilings)).getOrThrow()
            client.stop().getOrThrow()
            assertEquals(originalModes, modes())
            report.appendText("stop restored modes\n")
            // STOP replies before the old process finally closes its journal lock.
            Thread.sleep(1_000)
            client.ensureStarted(5_000).getOrThrow()
            client.applyProfile(request(ceilings)).getOrThrow()
            val beforeKill = client.readSnapshot().getOrThrow()
            killOwnedHost(context.filesDir)
            Thread.sleep(500)
            assertEquals(originalModes.map { it and 0x16d }, modes())
            client.ensureStarted(5_000).getOrThrow()
            val replacement = client.readSnapshot().getOrThrow()
            assertNotEquals(beforeKill.epoch, replacement.epoch)
            assertEquals(originalModes, modes())
            report.appendText("replacement host recovered modes; epoch=${replacement.epoch}\n")
        } finally {
            client.ensureStarted(5_000).getOrThrow()
            client.applyProfile(stock).getOrThrow()
            client.applyProfile(request(initial.state.cpuMax, maxOnly = true)).getOrThrow()
            client.stop().getOrThrow()
            assertEquals(originalModes, modes())
            report.appendText("cleanup restored initial ceilings and minimum permissions\n")
        }
    }

    /** A separate ADB harness force-stops the client after this explicitly requested fixture. */
    @Test fun prepareClientDeathFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("minimumLeaseDeath") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val method = PServerExecutionMethod()
        assumeTrue(method.probe().isAvailable)
        val client = ClusterTuneHostClient(context, PrivilegedExecutionResolver(listOf(method), listOf(method.id)))
        client.ensureStarted(5_000).getOrThrow()
        val snapshot = client.readSnapshot().getOrThrow()
        assumeTrue(snapshot.capabilities.cpus.all { cpu ->
            snapshot.state.cpuMax[snapshot.capabilities.cpus.indexOf(cpu)] < cpu.selectableMax
        })
        val original = snapshot.capabilities.cpus.map { Os.stat(it.minPath).st_mode and 0x1ff }
        try {
            client.applyProfile(ApplyRequest(snapshot.state.cpuMax, null, false, cpuIds = snapshot.capabilities.cpus.map { it.id })).getOrThrow()
            assertEquals(original.map { it and 0x16d }, snapshot.capabilities.cpus.map { Os.stat(it.minPath).st_mode and 0x1ff })
            File(context.filesDir, "minimum-lease-death-ready.txt").writeText("expected modes=$original; ceilings=${snapshot.state.cpuMax}")
        } catch (failure: Throwable) {
            client.stop().getOrThrow()
            throw failure
        }
        // Deliberately leave the lease alive. Its Binder death must trigger host cleanup.
    }

    private fun killOwnedHost(directory: File) {
        val output = File(directory, "minimum-kill-result.txt")
        output.delete()
        val script = File(directory, "minimum-kill-test.sh")
        // PServer executes as root. Restrict selection to the exact entry class AND this UID's
        // service and owner arguments, then require a single match before sending SIGKILL.
        script.writeText("""
            #!/system/bin/sh
            matches=""
            for proc in /proc/[0-9]*; do
              cmd=${'$'}(tr '\000' ' ' < "${'$'}proc/cmdline" 2>/dev/null)
              case "${'$'}cmd" in
                *"com.aure.clustertune.root.host.ClusterTuneHostEntry clustertune.host.${Process.myUid()} ${Process.myUid()} "*) matches="${'$'}matches ${'$'}{proc##*/}" ;;
              esac
            done
            set -- ${'$'}matches
            if [ "${'$'}#" = 1 ]; then kill -9 "${'$'}1" && echo "killed ${'$'}1" > '${output.path}'; else echo "unexpected matches ${'$'}matches" > '${output.path}'; fi
            chmod 644 '${output.path}'
        """.trimIndent())
        try {
            RootExec().launchHost("sh '${script.path}'").getOrThrow()
            repeat(100) { if (!output.exists()) Thread.sleep(100) }
            assertTrue(output.takeIf { it.exists() }?.readText().orEmpty().startsWith("killed "))
        } finally { script.delete(); output.delete() }
    }
}
