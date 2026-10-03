package com.aure.clustertune.root.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.autotune.AdaptiveCpuPolicy
import com.aure.clustertune.autotune.AdaptiveFrameMetrics
import com.aure.clustertune.autotune.AdaptiveFrequencyCeilings
import com.aure.clustertune.autotune.AdaptiveFrequencyController
import com.aure.clustertune.autotune.AdaptiveGpuDomain
import com.aure.clustertune.autotune.AdaptiveTuneConfig
import com.aure.clustertune.autotune.AdaptiveTuneDecision
import com.aure.clustertune.autotune.AdaptiveTuneEnvelope
import com.aure.clustertune.autotune.AdaptiveTuneReason
import com.aure.clustertune.autotune.AdaptiveTuneSample
import com.aure.clustertune.data.adaptiveAdjustableCpuPolicyIds
import com.aure.clustertune.root.PServerExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver
import com.aure.clustertune.root.RootShellExecutionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RootHostHandoffInstrumentationTest {
    @Test
    fun privilegedHostBinderHandoff_roundTripsThroughProductionClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = PrivilegedExecutionResolver.default(context)
        val method = resolver.autoDetectBestMethod(forceReprobe = true)
        assumeTrue("privileged execution unavailable", method != null)

        assertHostRoundTrip(resolver, method!!)
    }

    @Test
    fun pServerHostBinderHandoff_roundTripsWhenAvailable() {
        val method = PServerExecutionMethod()
        val probe = method.probe()
        assumeTrue("PServer unavailable: ${probe.failureReason}", probe.isAvailable)

        assertHostRoundTrip(
            resolver = PrivilegedExecutionResolver(listOf(method), listOf(method.id)),
            expectedMethod = method.id,
        )
    }

    @Test
    fun pServerAutoTune_preservesOwnedMinimumProtection() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("minimumProtection") == "1")
        val method = PServerExecutionMethod()
        assumeTrue(method.probe().isAvailable)
        assertHostRoundTrip(
            PrivilegedExecutionResolver(listOf(method), listOf(method.id)), method.id,
            protectProfileMinimums = true,
        )
    }

    @Test
    fun rootShellHostBinderHandoff_roundTripsWhenAvailable() {
        val method = RootShellExecutionMethod()
        val probe = method.probe()
        assumeTrue("root shell unavailable: ${probe.failureReason}", probe.isAvailable)

        assertHostRoundTrip(
            resolver = PrivilegedExecutionResolver(listOf(method), listOf(method.id)),
            expectedMethod = method.id,
        )
    }

    @Test
    fun watchdogRestoresCeilingsWhenClientStopsHeartbeating() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = PrivilegedExecutionResolver.default(context)
        assumeTrue("privileged execution unavailable", resolver.autoDetectBestMethod() != null)
        val client = ClusterTuneHostClient(context, resolver)
        var handle: HostAutoSessionHandle? = null
        var fixture: ActivityScenario<ComponentActivity>? = null
        var ownsHost = false
        try {
            client.ensureStarted(5_000).getOrThrow()
            val snapshot = client.readSnapshot().getOrThrow()
            val previous = client.readAutoTelemetry().getOrThrow()
            assumeTrue(previous.status != HostAutoSessionStatus.ACTIVE && previous.status != HostAutoSessionStatus.RESTORE_FAILED)
            ownsHost = true
            fixture = launchFrameFixture()
            val started = client.startAutoSession(AutoSessionRequest(context.packageName, 30, 5_000L)).getOrThrow()
            val session = requireNotNull(started.handle)
            handle = session
            val baseline = requireNotNull(started.state)
            val adjustable = adaptiveAdjustableCpuPolicyIds(snapshot.capabilities.cpus)
            val candidate = snapshot.capabilities.cpus.withIndex().firstOrNull { (index, cpu) ->
                cpu.policyId() in adjustable && baseline.cpuMin[index] > 0 &&
                    cpu.supportedFrequencies.any { it > baseline.cpuMin[index] && it < baseline.cpuMax[index] }
            }
            val gpuTarget = snapshot.capabilities.gpu?.supportedFrequencies?.filter {
                baseline.gpuMin != null && baseline.gpuMin > 0L &&
                    baseline.gpuMax != null && it > baseline.gpuMin && it < baseline.gpuMax
            }?.maxOrNull()
            assumeTrue("no safe lower CPU or GPU ceiling", candidate != null || gpuTarget != null)
            val ceilings = baseline.cpuMax.toMutableList()
            candidate?.let { (index, cpu) ->
                ceilings[index] = cpu.supportedFrequencies.filter {
                    it > baseline.cpuMin[index] && it < baseline.cpuMax[index]
                }.max()
            }
            val gpuCeiling = if (candidate == null) gpuTarget else baseline.gpuMax
            val applied = client.applyAutoStep(session, ApplyRequest(
                cpuMax = ceilings,
                gpuMax = gpuCeiling,
                resetToStock = false,
                cpuIds = snapshot.capabilities.cpus.map(CpuDomain::id),
                gpuId = snapshot.capabilities.gpu?.id,
                gpuMaxPath = snapshot.capabilities.gpu?.maxPath,
                maximumsOnly = true,
            )).getOrThrow()
            assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
            assertEquals(ceilings, requireNotNull(applied.state).cpuMax)
            assertEquals(gpuCeiling, requireNotNull(applied.state).gpuMax)

            // No further session calls: restoration must run in the host's own watchdog.
            Thread.sleep(7_000L)
            val afterWatchdog = client.readSnapshot().getOrThrow().state
            if (candidate != null) {
                assertEquals("watchdog must restore the owned CPU ceiling", baseline.cpuMax[candidate.index], afterWatchdog.cpuMax[candidate.index])
            } else {
                assertEquals("watchdog must restore the owned GPU ceiling", baseline.gpuMax, afterWatchdog.gpuMax)
            }
            val expired = client.readAutoTelemetry(session).getOrThrow()
            assertEquals(HostAutoSessionStatus.EXPIRED, expired.status)
            assertTrue(expired.restorationComplete)
        } finally {
            handle?.let { stopAndRestore(client, it) }
            fixture?.close()
            if (ownsHost) client.stop().getOrThrow()
        }
    }

    private fun assertHostRoundTrip(
        resolver: PrivilegedExecutionResolver,
        expectedMethod: String,
        protectProfileMinimums: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ClusterTuneHostClient(context, resolver)
        var sessionHandle: HostAutoSessionHandle? = null
        var fixture: ActivityScenario<ComponentActivity>? = null
        var safeToStopHost = false
        var minimumModes: Map<String, Int>? = null
        fun modes(paths: Set<String>) = paths.associateWith { android.system.Os.stat(it).st_mode and 0x1ff }
        try {
            client.ensureStarted(5_000).getOrThrow()
            assertEquals(expectedMethod, client.selectedMethodId)
            val snapshot = client.readSnapshot().getOrThrow()
            assertTrue(snapshot.capabilities.cpus.isNotEmpty())
            val autoCapabilities = client.readAutoCapabilities().getOrThrow()
            assertTrue(autoCapabilities.autoSessionSupported)
            assertTrue(autoCapabilities.frameBackend?.contains("surfaceflinger") == true)

            val preflight = client.readAutoTelemetry().getOrThrow()
            assumeTrue("an automatic session is already active", preflight.status != HostAutoSessionStatus.ACTIVE)
            assumeTrue("a prior automatic session has not restored", preflight.status != HostAutoSessionStatus.RESTORE_FAILED)
            safeToStopHost = true
            if (protectProfileMinimums) {
                val underclocked = snapshot.capabilities.cpus.filterIndexed { index, cpu ->
                    snapshot.state.cpuMax[index] < cpu.selectableMax
                }
                assumeTrue("an underclock profile is required", underclocked.isNotEmpty())
                minimumModes = modes(underclocked.map { it.minPath }.toSet())
                client.applyProfile(ApplyRequest(snapshot.state.cpuMax, null, false, cpuIds = snapshot.capabilities.cpus.map { it.id })).getOrThrow()
                assertEquals(minimumModes.mapValues { it.value and 0x16d }, modes(minimumModes.keys))
            }

            fixture = launchFrameFixture()
            val targetFps = 30
            val started = client.startAutoSession(
                AutoSessionRequest(
                    packageName = context.packageName,
                    targetFps = targetFps,
                    heartbeatTimeoutMs = 15_000L,
                ),
            ).getOrThrow()
            assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
            assertEquals(snapshot.epoch, started.hostEpoch)
            assertEquals(targetFps, started.targetFps)
            val handle = requireNotNull(started.handle)
            sessionHandle = handle
            val baseline = requireNotNull(started.state)
            val envelope = adaptiveEnvelope(snapshot.capabilities, baseline)
            val fixedCpuCeilings = envelope.cpuPolicies
                .filterNot(AdaptiveCpuPolicy::allowsAdaptiveAdjustment)
                .associate { it.policyId to it.baseCeilingKHz }
            println("Auto Tune fixed CPU policy ids: ${fixedCpuCeilings.keys.sorted()}")
            val controller = AdaptiveFrequencyController(
                config = AdaptiveTuneConfig(targetFps = targetFps),
                envelope = envelope,
            )
            assertEquals(
                snapshot.capabilities.cpus.map { it.policyId() }.toSet(),
                controller.baseCeilings.cpuKHz.keys,
            )
            assertFixedCpuCeilings(fixedCpuCeilings, controller.baseCeilings)

            var afterSequence = -1L
            var firstTimestamp = -1L
            var latest: HostAutoTelemetry? = null
            var freshFrameSampleSeen = false
            var controllerApply: AdaptiveTuneDecision.Apply? = null
            var appliedState: HostState? = null
            for (attempt in 0 until 16) {
                Thread.sleep(400)
                client.heartbeatAutoSession(handle).getOrThrow()
                val sampled = client.readAutoTelemetry(handle, afterSequence).getOrThrow()
                assertEquals(HostAutoSessionStatus.ACTIVE, sampled.status)
                val telemetry = requireNotNull(sampled.telemetry)
                assertTrue(telemetry.sequence > afterSequence)
                assertTrue(telemetry.timestampNanos > firstTimestamp)
                assertEquals(snapshot.capabilities.cpus.size, telemetry.cpuLoadPermille.size)
                afterSequence = telemetry.sequence
                firstTimestamp = telemetry.timestampNanos
                latest = telemetry
                freshFrameSampleSeen = freshFrameSampleSeen || (
                    telemetry.frameBackend?.contains("surfaceflinger") == true &&
                        !telemetry.frameLayer.isNullOrBlank() &&
                        telemetry.frameCount > 0 &&
                        !telemetry.frameStale &&
                        telemetry.fpsMilli != null
                )
                val decision = controller.step(telemetry.toAdaptiveTuneSample(snapshot.capabilities))
                assertEquals(
                    snapshot.capabilities.cpus.map { it.policyId() }.toSet(),
                    decision.ceilings.cpuKHz.keys,
                )
                assertFixedCpuCeilings(fixedCpuCeilings, decision.ceilings)
                if (decision is AdaptiveTuneDecision.Apply) {
                    val request = decision.toApplyRequest(snapshot.capabilities)
                    val applied = client.applyAutoStep(handle, request).getOrThrow()
                    assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
                    appliedState = requireNotNull(applied.state)
                    assertEquals(request.cpuMax, appliedState.cpuMax)
                    assertEquals(request.gpuMax, appliedState.gpuMax)
                    fixedCpuCeilings.forEach { (policyId, expectedCeiling) ->
                        val index = snapshot.capabilities.cpus.indexOfFirst { it.policyId() == policyId }
                        assertTrue("fixed CPU policy$policyId disappeared", index >= 0)
                        assertEquals(
                            "fixed CPU policy$policyId changed during Auto Tune",
                            expectedCeiling,
                            appliedState.cpuMax[index],
                        )
                    }
                    controllerApply = decision
                    break
                }
            }
            assertTrue(latest?.frameBackend?.contains("surfaceflinger") == true)
            assertTrue("no fresh SurfaceFlinger frame sample was observed", freshFrameSampleSeen)
            val appliedDecision = requireNotNull(controllerApply) {
                "Auto Tune did not make a live efficiency-trim decision"
            }
            assertEquals(AdaptiveTuneReason.EFFICIENCY_TRIM, appliedDecision.reason)
            assertTrue(appliedDecision.change.toCeiling < appliedDecision.change.fromCeiling)
            val automaticState = requireNotNull(appliedState)
            assertEquals(baseline.cpuMin, automaticState.cpuMin)
            assertEquals(baseline.gpuMin, automaticState.gpuMin)

            val stopped = stopAndRestore(client, handle)
            assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
            assertTrue(stopped.restorationAttempted)
            assertTrue(stopped.restorationComplete)
            assertAutomaticCeilingsReleased(
                baseline = baseline,
                automatic = automaticState,
                actual = client.readSnapshot().getOrThrow().state,
            )
            minimumModes?.let { assertEquals(it.mapValues { entry -> entry.value and 0x16d }, modes(it.keys)) }
            sessionHandle = null
        } finally {
            var cleanupFailure: Throwable? = null
            sessionHandle?.let { handle ->
                runCatching { stopAndRestore(client, handle) }
                    .onFailure { cleanupFailure = it }
            }
            runCatching { fixture?.close() }
                .onFailure { failure -> cleanupFailure = cleanupFailure ?: failure }
            if (safeToStopHost && cleanupFailure == null) {
                client.stop().exceptionOrNull()?.let { cleanupFailure = it }
                if (cleanupFailure == null) minimumModes?.let { assertEquals(it, modes(it.keys)) }
            }
            cleanupFailure?.let { throw it }
        }
    }

    private fun launchFrameFixture(): ActivityScenario<ComponentActivity> {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity -> activity.setContentView(FrameFixtureView(activity)) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(500)
        return scenario
    }

    private fun stopAndRestore(
        client: ClusterTuneHostClient,
        handle: HostAutoSessionHandle,
    ): HostAutoSessionSnapshot {
        var latest: HostAutoSessionSnapshot? = null
        repeat(10) {
            latest = client.stopAutoSession(handle).getOrThrow()
            if (latest?.restorationComplete == true) return requireNotNull(latest)
            Thread.sleep(500)
        }
        error("automatic session restoration remained incomplete: ${latest?.message.orEmpty()}")
    }

    private fun adaptiveEnvelope(
        capabilities: HostCapabilities,
        baseline: HostState,
    ): AdaptiveTuneEnvelope {
        val adjustableCpuPolicyIds = adaptiveAdjustableCpuPolicyIds(capabilities.cpus)
        return AdaptiveTuneEnvelope(
            cpuPolicies = capabilities.cpus.mapIndexed { index, domain ->
                val base = baseline.cpuMax[index]
                val floor = baseline.cpuMin[index]
                AdaptiveCpuPolicy(
                    policyId = domain.policyId(),
                    availableCeilingsKHz = if (floor > 0L) {
                        domain.supportedFrequencies.filter { it > floor && it <= base }
                    } else {
                        emptyList()
                    },
                    baseCeilingKHz = base,
                    allowsAdaptiveAdjustment = domain.policyId() in adjustableCpuPolicyIds,
                )
            },
            gpu = capabilities.gpu?.let { domain ->
                val base = requireNotNull(baseline.gpuMax)
                val floor = baseline.gpuMin
                AdaptiveGpuDomain(
                    id = domain.id,
                    availableCeilingsHz = if (floor != null && floor > 0L) {
                        domain.supportedFrequencies.filter { it > floor && it <= base }
                    } else {
                        emptyList()
                    },
                    baseCeilingHz = base,
                )
            },
        )
    }

    private fun assertFixedCpuCeilings(
        expected: Map<Int, Long>,
        actual: AdaptiveFrequencyCeilings,
    ) {
        expected.forEach { (policyId, expectedCeiling) ->
            assertEquals(
                "fixed CPU policy$policyId changed in the complete controller envelope",
                expectedCeiling,
                actual.cpuKHz[policyId],
            )
        }
    }

    private fun HostAutoTelemetry.toAdaptiveTuneSample(
        capabilities: HostCapabilities,
    ): AdaptiveTuneSample {
        val frames = fpsMilli?.takeIf { it > 0 && frameConfidencePermille >= 250 }?.let { fps ->
            AdaptiveFrameMetrics(
                fps = fps / 1_000.0,
                p95FrameTimeMillis = frameTimeP95Nanos?.takeIf { it > 0L }?.div(1_000_000.0),
                slowFrameRatio = slowFrameRatioPermille?.coerceIn(0, 1_000)?.div(1_000.0),
                isStale = frameStale || frameConfidencePermille < 250,
            )
        }
        return AdaptiveTuneSample(
            timestampNanos = timestampNanos,
            frames = frames,
            cpuLoad = capabilities.cpus.mapIndexed { index, domain ->
                domain.policyId() to cpuLoadPermille.getOrNull(index)?.coerceIn(0, 1_000)?.div(1_000.0)
            }.toMap(),
            gpuBusy = gpuBusyPermille?.coerceIn(0, 1_000)?.div(1_000.0),
        )
    }

    private fun AdaptiveTuneDecision.Apply.toApplyRequest(
        capabilities: HostCapabilities,
    ): ApplyRequest = ApplyRequest(
        cpuMax = capabilities.cpus.map { domain -> ceilings.cpuKHz.getValue(domain.policyId()) },
        gpuMax = capabilities.gpu?.let { requireNotNull(ceilings.gpuHz) },
        resetToStock = false,
        cpuIds = capabilities.cpus.map(CpuDomain::id),
        gpuId = capabilities.gpu?.id,
        gpuMaxPath = capabilities.gpu?.maxPath,
        maximumsOnly = true,
    )

    private fun CpuDomain.policyId(): Int = id.removePrefix("policy").toInt()

    private fun assertAutomaticCeilingsReleased(
        baseline: HostState,
        automatic: HostState,
        actual: HostState,
    ) {
        assertEquals(baseline.cpuMax.size, actual.cpuMax.size)
        baseline.cpuMax.indices.forEach { index ->
            if (automatic.cpuMax[index] != baseline.cpuMax[index]) {
                assertTrue(
                    "automatic CPU ceiling remained on policy $index",
                    actual.cpuMax[index] == baseline.cpuMax[index] || actual.cpuMax[index] != automatic.cpuMax[index],
                )
            }
        }
        if (automatic.gpuMax != baseline.gpuMax) {
            assertTrue(
                "automatic GPU ceiling remained after stop",
                actual.gpuMax == baseline.gpuMax || actual.gpuMax != automatic.gpuMax,
            )
        }
    }

    private class FrameFixtureView(context: Context) : View(context) {
        private var frame = 0

        override fun onDraw(canvas: Canvas) {
            frame++
            canvas.drawColor(if (frame % 2 == 0) Color.rgb(22, 28, 40) else Color.rgb(24, 30, 44))
            postInvalidateOnAnimation()
        }
    }
}
