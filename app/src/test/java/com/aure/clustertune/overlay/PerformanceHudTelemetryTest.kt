package com.aure.clustertune.overlay

import com.aure.clustertune.root.host.HostAutoTelemetry
import com.aure.clustertune.root.host.HostTelemetrySessionHandle
import com.aure.clustertune.root.host.HostTelemetrySessionSnapshot
import com.aure.clustertune.root.host.HostTelemetrySessionStatus
import com.aure.clustertune.root.host.TelemetrySessionRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceHudTelemetryTest {
    @Test
    fun `device-only request streams metrics and exact handle is stopped`() = runTest {
        val backend = FakeBackend()
        val states = mutableListOf<PerformanceHudTelemetryState>()
        val job = launch {
            PerformanceHudTelemetryRunner(backend, sampleIntervalMillis = 100L).run(
                PerformanceHudTelemetryTarget(packageName = null, targetFps = 0),
                states::add,
            )
        }

        runCurrent()
        job.cancelAndJoin()

        assertEquals(TelemetrySessionRequest(), backend.startedRequest)
        assertEquals(backend.handle, backend.stoppedHandle)
        assertTrue(states.any { it.sample?.sequence == 1L })
        assertTrue(states.filter { it.sample != null }.all { it.streamId == backend.handle.sessionId })
    }

    private class FakeBackend : PerformanceHudTelemetryBackend {
        val handle = HostTelemetrySessionHandle("hud", 9L)
        var startedRequest: TelemetrySessionRequest? = null
        var stoppedHandle: HostTelemetrySessionHandle? = null
        private var sequence = 0L

        override suspend fun start(request: TelemetrySessionRequest): HostTelemetrySessionSnapshot {
            startedRequest = request
            return snapshot(HostTelemetrySessionStatus.ACTIVE)
        }

        override suspend fun read(
            handle: HostTelemetrySessionHandle,
            afterSequence: Long,
        ): HostTelemetrySessionSnapshot {
            sequence++
            return snapshot(HostTelemetrySessionStatus.ACTIVE, telemetry(sequence))
        }

        override suspend fun stop(handle: HostTelemetrySessionHandle): HostTelemetrySessionSnapshot {
            stoppedHandle = handle
            return snapshot(HostTelemetrySessionStatus.STOPPED)
        }

        private fun snapshot(
            status: HostTelemetrySessionStatus,
            telemetry: HostAutoTelemetry? = null,
        ) = HostTelemetrySessionSnapshot(
            sessionId = handle.sessionId,
            hostEpoch = handle.hostEpoch,
            status = status,
            targetFps = 0,
            telemetry = telemetry,
        )

        private fun telemetry(sequence: Long) = HostAutoTelemetry(
            sequence = sequence,
            timestampNanos = sequence,
            frameBackend = null,
            frameConfidencePermille = 0,
            frameLayer = null,
            frameCount = 0,
            fpsMilli = null,
            frameTimeP95Nanos = null,
            slowFrameRatioPermille = null,
            frameStale = true,
            cpuLoadPermille = listOf(500),
            cpuClockKHz = listOf(1_000_000L),
            gpuBusyPermille = 250,
            gpuClockHz = 400_000_000L,
            thermal = emptyList(),
        )
    }
}
