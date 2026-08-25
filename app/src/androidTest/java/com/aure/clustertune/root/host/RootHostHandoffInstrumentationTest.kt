package com.aure.clustertune.root.host

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    fun rootShellHostBinderHandoff_roundTripsWhenAvailable() {
        val method = RootShellExecutionMethod()
        val probe = method.probe()
        assumeTrue("root shell unavailable: ${probe.failureReason}", probe.isAvailable)

        assertHostRoundTrip(
            resolver = PrivilegedExecutionResolver(listOf(method), listOf(method.id)),
            expectedMethod = method.id,
        )
    }

    private fun assertHostRoundTrip(
        resolver: PrivilegedExecutionResolver,
        expectedMethod: String,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = ClusterTuneHostClient(context, resolver)
        var sessionHandle: HostAutoSessionHandle? = null
        var fixture: ActivityScenario<ComponentActivity>? = null
        var safeToStopHost = false
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

            fixture = launchFrameFixture()
            val started = client.startAutoSession(
                AutoSessionRequest(
                    packageName = context.packageName,
                    targetFps = 60,
                    heartbeatTimeoutMs = 15_000L,
                ),
            ).getOrThrow()
            assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
            assertEquals(snapshot.epoch, started.hostEpoch)
            assertEquals(60, started.targetFps)
            val handle = requireNotNull(started.handle)
            sessionHandle = handle
            val baseline = requireNotNull(started.state)
            assertStableState(snapshot.state, baseline)

            var afterSequence = -1L
            var firstTimestamp = -1L
            var latest: HostAutoTelemetry? = null
            var freshFrameSampleSeen = false
            repeat(4) {
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
            }
            assertTrue(latest?.frameBackend?.contains("surfaceflinger") == true)
            assertTrue("no fresh SurfaceFlinger frame sample was observed", freshFrameSampleSeen)

            val requestedCpu = baseline.cpuMax.toMutableList()
            // Prefer crossing a live OEM floor when the device exposes one. The
            // automatic path must still write only scaling_max_freq.
            val lowerCpuStep = snapshot.capabilities.cpus.indices
                .flatMap { index ->
                    snapshot.capabilities.cpus[index].supportedFrequencies
                        .filter { it in 1 until baseline.cpuMax[index] }
                        .map { value -> Triple(index, value, value < (baseline.cpuMin.getOrNull(index) ?: 1L)) }
                }
                .sortedWith(compareByDescending<Triple<Int, Long, Boolean>> { it.third }.thenByDescending { it.second })
                .firstOrNull()
            var requestedGpu = baseline.gpuMax
            if (lowerCpuStep != null) {
                val (index, value) = lowerCpuStep
                requestedCpu[index] = value
            } else {
                requestedGpu = snapshot.capabilities.gpu?.supportedFrequencies
                    ?.filter { current -> baseline.gpuMax?.let { current in 1 until it } == true }
                    ?.maxOrNull()
                    ?: error("no safe lower CPU or GPU frequency is available for the mutation check")
            }
            val applied = client.applyAutoStep(
                handle,
                ApplyRequest(
                    cpuMax = requestedCpu,
                    gpuMax = requestedGpu,
                    resetToStock = false,
                    cpuIds = snapshot.capabilities.cpus.map(CpuDomain::id),
                    gpuId = snapshot.capabilities.gpu?.id,
                    gpuMaxPath = snapshot.capabilities.gpu?.maxPath,
                    maximumsOnly = true,
                ),
            ).getOrThrow()
            assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
            val appliedState = requireNotNull(applied.state)
            assertEquals(requestedCpu, appliedState.cpuMax)
            assertEquals(requestedGpu, appliedState.gpuMax)

            val stopped = stopAndRestore(client, handle)
            assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
            assertTrue(stopped.restorationAttempted)
            assertTrue(stopped.restorationComplete)
            assertAutomaticCeilingsReleased(
                baseline = snapshot.state,
                automatic = appliedState,
                actual = client.readSnapshot().getOrThrow().state,
            )
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

    private fun assertStableState(expected: HostState, actual: HostState) {
        assertEquals(expected.cpuMax, actual.cpuMax)
        assertEquals(expected.cpuMin, actual.cpuMin)
        assertEquals(expected.gpuMax, actual.gpuMax)
        assertEquals(expected.gpuMin, actual.gpuMin)
    }

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
