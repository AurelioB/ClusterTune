package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTelemetrySessionTest {
    @Test
    fun `device-only session is active without starting SurfaceFlinger`() {
        val fixture = fixture()

        val started = fixture.controller.start(TelemetrySessionRequest())
        val sample = fixture.controller.read(started.sessionId!!, started.hostEpoch)

        assertEquals(HostTelemetrySessionStatus.ACTIVE, started.status)
        assertEquals(0, started.targetFps)
        assertTrue(fixture.source.beginCalls.isEmpty())
        assertEquals(1, fixture.source.sampleCount)
        assertEquals(listOf(500), sample.telemetry!!.cpuLoadPermille)
        assertEquals(listOf(700_000L), sample.telemetry!!.cpuClockKHz)
        assertEquals(600, sample.telemetry!!.gpuBusyPermille)
        assertEquals(450_000_000L, sample.telemetry!!.gpuClockHz)
        assertEquals(HostThermalReading("soc", 42_000L), sample.telemetry!!.thermal.single())
        assertTrue("frame_session_not_started" in sample.telemetry!!.unsupportedMetrics)
    }

    @Test
    fun `package session starts frame source and reuses HostAutoTelemetry payload`() {
        val fixture = fixture()
        val started = fixture.controller.start(
            TelemetrySessionRequest("com.game", targetFps = 120, heartbeatTimeoutMs = 5_000L),
        )

        val sampled = fixture.controller.read(started.sessionId!!, started.hostEpoch)

        assertEquals(listOf("com.game" to 120), fixture.source.beginCalls)
        assertEquals(HostTelemetrySessionStatus.ACTIVE, sampled.status)
        assertEquals(1L, sampled.telemetry!!.sequence)
        assertEquals(120_000, sampled.telemetry!!.fpsMilli)
        assertEquals("surfaceflinger-latency", sampled.telemetry!!.frameBackend)
    }

    @Test
    fun `device-only session remains available when frame telemetry is unsupported`() {
        val fixture = fixture(frameStats = false)

        val deviceOnly = fixture.controller.start(TelemetrySessionRequest())

        assertEquals(HostTelemetrySessionStatus.ACTIVE, deviceOnly.status)
        assertTrue(fixture.source.beginCalls.isEmpty())
        val stopped = fixture.controller.stop(deviceOnly.sessionId!!, deviceOnly.hostEpoch)
        assertEquals(HostTelemetrySessionStatus.STOPPED, stopped.status)

        val framed = fixture.controller.start(frameRequest())
        assertEquals(HostTelemetrySessionStatus.UNAVAILABLE, framed.status)
        assertNull(framed.handle)
        assertTrue(fixture.source.beginCalls.isEmpty())
    }

    @Test
    fun `wrong handle and epoch cannot read renew or stop active session`() {
        val fixture = fixture()
        val started = fixture.controller.start(frameRequest())
        val sessionId = started.sessionId!!

        assertEquals(
            HostTelemetrySessionStatus.STALE,
            fixture.controller.stop("different", started.hostEpoch).status,
        )
        assertEquals(
            HostTelemetrySessionStatus.STALE,
            fixture.controller.read(sessionId, started.hostEpoch + 1L).status,
        )
        assertEquals(0, fixture.source.endCount)
        assertEquals(0, fixture.source.sampleCount)

        val live = fixture.controller.read(sessionId, started.hostEpoch)
        assertEquals(HostTelemetrySessionStatus.ACTIVE, live.status)
        assertEquals(1, fixture.source.sampleCount)
    }

    @Test
    fun `exact stop is idempotent and never performs hardware ownership work`() {
        val fixture = fixture()
        val started = fixture.controller.start(frameRequest())

        val stopped = fixture.controller.stop(started.sessionId!!, started.hostEpoch)
        val stoppedAgain = fixture.controller.stop(started.sessionId!!, started.hostEpoch)

        assertEquals(HostTelemetrySessionStatus.STOPPED, stopped.status)
        assertEquals(stopped, stoppedAgain)
        assertEquals(1, fixture.source.endCount)
        assertEquals(0, fixture.source.sampleCount)
    }

    @Test
    fun `read renews watchdog and expiry ends telemetry source`() {
        val fixture = fixture()
        val started = fixture.controller.start(frameRequest())
        val sessionId = started.sessionId!!
        fixture.clock.now = 4_000_000_000L
        fixture.controller.read(sessionId, started.hostEpoch)

        fixture.clock.now = 6_000_000_000L
        assertNull(fixture.controller.expireIfNeeded())
        assertEquals(0, fixture.source.endCount)

        fixture.clock.now = 9_000_000_000L
        val expired = fixture.controller.expireIfNeeded()
        assertEquals(HostTelemetrySessionStatus.EXPIRED, expired!!.status)
        assertEquals(1, fixture.source.endCount)
        assertEquals(expired, fixture.controller.stop(sessionId, started.hostEpoch))
    }

    @Test
    fun `cached read avoids duplicate expensive sample and still renews watchdog`() {
        val fixture = fixture()
        val started = fixture.controller.start(frameRequest())
        val first = fixture.controller.read(started.sessionId!!, started.hostEpoch)
        fixture.clock.now = 4_000_000_000L

        val cached = fixture.controller.read(
            started.sessionId!!,
            started.hostEpoch,
            afterSequence = 0L,
        )

        assertEquals(first.telemetry, cached.telemetry)
        assertEquals(1, fixture.source.sampleCount)
        fixture.clock.now = 6_000_000_000L
        assertNull(fixture.controller.expireIfNeeded())
    }

    @Test
    fun `Auto Tune ownership is typed unavailable and never starts duplicate telemetry`() {
        val fixture = fixture()

        val unavailable = fixture.controller.start(frameRequest(), telemetryAvailable = false)

        assertEquals(HostTelemetrySessionStatus.UNAVAILABLE, unavailable.status)
        assertNull(unavailable.handle)
        assertTrue(unavailable.message.orEmpty().contains("Auto Tune"))
        assertTrue(fixture.source.beginCalls.isEmpty())
        assertEquals(0, fixture.source.sampleCount)
    }

    @Test
    fun `Auto Tune taking ownership ends an existing monitor`() {
        val fixture = fixture()
        val started = fixture.controller.start(frameRequest())

        val unavailable = fixture.controller.read(
            started.sessionId!!,
            started.hostEpoch,
            telemetryAvailable = false,
        )

        assertEquals(HostTelemetrySessionStatus.UNAVAILABLE, unavailable.status)
        assertNotNull(unavailable.handle)
        assertEquals(1, fixture.source.endCount)
        assertEquals(0, fixture.source.sampleCount)
    }

    @Test
    fun `frame and device-only request shapes are validated`() {
        val fixture = fixture()

        assertTrue(runCatching {
            fixture.controller.start(TelemetrySessionRequest(null, targetFps = 60))
        }.isFailure)
        assertTrue(runCatching {
            fixture.controller.start(TelemetrySessionRequest("com.game", targetFps = 0))
        }.isFailure)
        assertTrue(runCatching {
            fixture.controller.start(TelemetrySessionRequest("not a package", targetFps = 60))
        }.isFailure)
        assertTrue(fixture.source.beginCalls.isEmpty())
    }

    @Test
    fun `protocol exposes telemetry transactions with the current host generation`() {
        assertEquals(13, HostProtocol.VERSION)
        assertEquals(17, HostProtocol.START_TELEMETRY_SESSION)
        assertEquals(18, HostProtocol.READ_TELEMETRY_SESSION)
        assertEquals(19, HostProtocol.STOP_TELEMETRY_SESSION)
    }

    private fun fixture(frameStats: Boolean = true): Fixture {
        val source = RecordingTelemetrySource(frameStats)
        val clock = TelemetryClock()
        val capabilities = HostCapabilities(
            cpus = listOf(
                CpuDomain(
                    id = "policy0",
                    minPath = "cpu-min",
                    maxPath = "cpu-max",
                    curPath = "cpu-cur",
                    minimumCandidates = emptyList(),
                    supportedFrequencies = emptyList(),
                    stockMax = 1_000_000L,
                    observedMax = 1_000_000L,
                    observedMin = 300_000L,
                ),
            ),
            gpu = GpuDomain(id = "gpu", minPath = null, maxPath = "gpu-max", curPath = "gpu-cur"),
        )
        return Fixture(
            controller = HostTelemetrySessionController(capabilities, source, HOST_EPOCH, clock),
            source = source,
            clock = clock,
        )
    }

    private fun frameRequest() = TelemetrySessionRequest(
        packageName = "com.game",
        targetFps = 60,
        heartbeatTimeoutMs = 5_000L,
    )

    private data class Fixture(
        val controller: HostTelemetrySessionController,
        val source: RecordingTelemetrySource,
        val clock: TelemetryClock,
    )

    private class TelemetryClock(var now: Long = 0L) : HostMonotonicClock {
        override fun nanoTime(): Long = now
    }

    private class RecordingTelemetrySource(
        private val frameStats: Boolean,
    ) : HostTelemetrySource {
        val beginCalls = mutableListOf<Pair<String, Int>>()
        var sampleCount = 0
        var endCount = 0

        override fun capabilities() = HostAutoCapabilities(
            frameStats = frameStats,
            cpuLoad = true,
            cpuClocks = true,
            gpuBusy = true,
            gpuClock = true,
            thermal = true,
            frameBackend = if (frameStats) "surfaceflinger-latency" else null,
            unsupportedReason = if (frameStats) null else "frame telemetry unavailable",
        )

        override fun begin(packageName: String, targetFps: Int): Result<Unit> {
            beginCalls += packageName to targetFps
            return Result.success(Unit)
        }

        override fun sample(): HostRawTelemetry {
            sampleCount++
            return HostRawTelemetry(
                timestampNanos = sampleCount.toLong(),
                frameBackend = "surfaceflinger-latency",
                frameConfidencePermille = 1_000,
                frameLayer = "game-layer",
                frameCount = 2,
                fpsMilli = 120_000,
                frameTimeP95Nanos = 9_000_000L,
                slowFrameRatioPermille = 10,
                frameStale = false,
                cpuLoadPermille = listOf(500),
                cpuClockKHz = listOf(700_000L),
                gpuBusyPermille = 600,
                gpuClockHz = 450_000_000L,
                thermal = listOf(HostThermalReading("soc", 42_000L)),
                unsupportedMetrics = listOf("frame_session_not_started"),
            )
        }

        override fun end() {
            endCount++
        }
    }

    private companion object {
        const val HOST_EPOCH = 42L
    }
}
