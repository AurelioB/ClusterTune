package com.aure.clustertune.ui

import com.aure.clustertune.autotune.AdaptiveTuneReason
import com.aure.clustertune.autotune.AdaptiveTuneRuntimeState
import com.aure.clustertune.autotune.AdaptiveTuneStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTuneRuntimePresentationTest {

    @Test
    fun `inactive runtime without a terminal message is hidden`() {
        assertNull(autoTuneRuntimePresentation(AdaptiveTuneRuntimeState()))
        assertNull(
            autoTuneRuntimePresentation(
                AdaptiveTuneRuntimeState(
                    active = false,
                    packageName = "example.game",
                    status = AdaptiveTuneStatus.STOPPED,
                    reason = AdaptiveTuneReason.REQUESTED_STOP,
                    message = "   ",
                ),
            ),
        )
    }

    @Test
    fun `active runtime presents target metrics utilization status and backend`() {
        val presentation = autoTuneRuntimePresentation(
            AdaptiveTuneRuntimeState(
                active = true,
                packageName = "example.game",
                appLabel = "Example Game",
                targetFps = 60,
                status = AdaptiveTuneStatus.RECOVERING,
                reason = AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY,
                measuredFps = 57.34,
                p95FrameTimeMillis = 20.56,
                cpuLoad = mapOf(6 to 0.82, 0 to 0.41, 4 to null),
                gpuBusy = 0.67,
                frameBackend = "surfaceflinger-latency",
                message = "Raising a CPU ceiling",
            ),
        )

        requireNotNull(presentation)
        assertTrue(presentation.active)
        assertEquals("Example Game · Target 60 FPS", presentation.appAndTarget)
        assertEquals("Recovering", presentation.status)
        assertEquals("57.3 FPS · P95 20.6 ms", presentation.frameMetrics)
        assertEquals("CPU C0 41% · CPU C6 82% · GPU 67%", presentation.utilization)
        assertEquals("Raising a CPU ceiling", presentation.message)
        assertEquals("surfaceflinger-latency", presentation.frameBackend)
    }

    @Test
    fun `terminal message remains visible and uses package fallback`() {
        val presentation = autoTuneRuntimePresentation(
            AdaptiveTuneRuntimeState(
                active = false,
                packageName = "example.game",
                targetFps = 120,
                status = AdaptiveTuneStatus.STOPPED,
                reason = AdaptiveTuneReason.REQUESTED_STOP,
                measuredFps = 118.0,
                message = "Stopped when the app left the foreground",
            ),
        )

        requireNotNull(presentation)
        assertFalse(presentation.active)
        assertEquals("example.game · Target 120 FPS", presentation.appAndTarget)
        assertEquals("Stopped", presentation.status)
        assertEquals("118.0 FPS", presentation.frameMetrics)
        assertEquals("Stopped when the app left the foreground", presentation.message)
    }

    @Test
    fun `active runtime falls back to a readable reason and sanitizes telemetry`() {
        val presentation = autoTuneRuntimePresentation(
            AdaptiveTuneRuntimeState(
                active = true,
                status = AdaptiveTuneStatus.WAITING_FOR_FRAMES,
                reason = AdaptiveTuneReason.FRAME_DATA_STALE,
                measuredFps = Double.NaN,
                p95FrameTimeMillis = -1.0,
                cpuLoad = mapOf(0 to 1.4, 4 to Double.NaN),
                gpuBusy = -0.2,
            ),
        )

        requireNotNull(presentation)
        assertEquals("Current app", presentation.appAndTarget)
        assertEquals("Waiting for frames", presentation.status)
        assertNull(presentation.frameMetrics)
        assertEquals("CPU C0 100% · GPU 0%", presentation.utilization)
        assertEquals("Waiting for frame data", presentation.message)
        assertNull(presentation.frameBackend)
    }
}
