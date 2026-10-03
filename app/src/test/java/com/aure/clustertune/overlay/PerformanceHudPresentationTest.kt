package com.aure.clustertune.overlay

import com.aure.clustertune.autotune.AdaptiveTuneRuntimeState
import com.aure.clustertune.root.host.HostAutoTelemetry
import com.aure.clustertune.root.host.HostThermalReading
import com.aure.clustertune.ui.PerformanceHudTuneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceHudPresentationTest {
    @Test
    fun `read-only telemetry maps operating metrics and ignores battery heat`() {
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry(),
            autoTune = AdaptiveTuneRuntimeState(),
            autoTunePackageName = null,
            configuredAutoTuneTargetFps = null,
            displayRefreshRateFps = 120,
            effectiveProfileName = "Balanced",
            vendorState = PerformanceHudVendorState("Medium", "Smart"),
        )

        assertEquals(59.94, model.framesPerSecond!!, 0.001)
        assertEquals(75.0, model.cpuLoadPercent!!, 0.001)
        assertEquals(2_000_000L, model.cpuClockKHz)
        assertEquals(52.5, model.gpuBusyPercent!!, 0.001)
        assertEquals(70.0, model.maxTemperatureCelsius!!, 0.001)
        assertEquals("Balanced", (model.tuneMode as PerformanceHudTuneMode.FixedProfile).profileName)
        assertEquals("Medium", model.oemPerformanceProfile)
        assertEquals("Smart", model.fanProfile)
    }

    @Test
    fun `active Auto Tune data wins and exposes refresh bounded slider`() {
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry(),
            autoTune = AdaptiveTuneRuntimeState(
                active = true,
                targetFps = 30,
                measuredFps = 30.1,
                cpuLoad = mapOf(0 to 0.2, 7 to 0.9),
                cpuClockKHz = mapOf(0 to 900_000L, 7 to 2_600_000L),
                gpuBusy = 0.8,
                gpuClockHz = 600_000_000L,
                thermalMilliCelsius = mapOf("soc" to 71_000L),
            ),
            autoTunePackageName = "com.example.game",
            configuredAutoTuneTargetFps = 30,
            displayRefreshRateFps = 60,
            effectiveProfileName = "Auto Tune · 30 FPS",
            vendorState = PerformanceHudVendorState(),
        )

        assertEquals(30.1, model.framesPerSecond!!, 0.001)
        assertEquals(90.0, model.cpuLoadPercent!!, 0.001)
        assertEquals(2_600_000L, model.cpuClockKHz)
        assertEquals(1..60, (model.tuneMode as PerformanceHudTuneMode.AutoTune).targetRange)
    }

    @Test
    fun `configured Auto Tune target is capped to the physical display refresh rate`() {
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry(),
            autoTune = AdaptiveTuneRuntimeState(),
            autoTunePackageName = "com.example.game",
            configuredAutoTuneTargetFps = 120,
            displayRefreshRateFps = 60,
            effectiveProfileName = "Auto Tune · 120 FPS",
            vendorState = PerformanceHudVendorState(),
        )

        val tuneMode = model.tuneMode as PerformanceHudTuneMode.AutoTune
        assertEquals(60, tuneMode.targetFps)
        assertEquals(1..60, tuneMode.targetRange)
        assertEquals(120, tuneMode.configuredTargetFps)
        assertEquals(60f, model.frameRateGraphMaximumFps)
    }

    @Test
    fun `stale low confidence frames are omitted`() {
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry().copy(frameStale = true, frameConfidencePermille = 100),
            autoTune = AdaptiveTuneRuntimeState(),
            autoTunePackageName = null,
            configuredAutoTuneTargetFps = null,
            displayRefreshRateFps = null,
            effectiveProfileName = null,
            vendorState = PerformanceHudVendorState(),
        )
        assertNull(model.framesPerSecond)
        assertNull(model.p95FrameTimeMillis)
    }

    @Test
    fun `fresh low confidence frame metrics are omitted together`() {
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry().copy(frameStale = false, frameConfidencePermille = 100),
            autoTune = AdaptiveTuneRuntimeState(),
            autoTunePackageName = null,
            configuredAutoTuneTargetFps = null,
            displayRefreshRateFps = 60,
            effectiveProfileName = null,
            vendorState = PerformanceHudVendorState(),
        )

        assertNull(model.framesPerSecond)
        assertNull(model.p95FrameTimeMillis)
    }

    @Test
    fun `Auto Tune target supplies FPS graph ceiling when display refresh is unavailable`() {
        val model = buildPerformanceHudUiModel(
            telemetry = null,
            autoTune = AdaptiveTuneRuntimeState(
                active = true,
                targetFps = 77,
                measuredFps = 76.5,
            ),
            autoTunePackageName = "com.example.game",
            configuredAutoTuneTargetFps = 77,
            displayRefreshRateFps = null,
            effectiveProfileName = null,
            vendorState = PerformanceHudVendorState(),
        )

        assertEquals(77f, model.frameRateGraphMaximumFps)
    }

    @Test
    fun `graph accumulator records paired gaps and ignores duplicate identities`() {
        val source = PerformanceHudGraphSource("monitor:one", "com.example.game")
        val accumulator = PerformanceHudGraphAccumulator(capacity = 3)

        accumulator.record(source, 1L, 30.0, 25.0, 75.0)
        accumulator.record(source, 2L, null, 50.0, null)
        val duplicate = accumulator.record(source, 2L, 99.0, 99.0, 99.0)
        val result = accumulator.record(source, 3L, 60.0, null, 100.0)

        assertEquals(listOf(30f, null, 60f), result.frameRateFps)
        assertEquals(listOf(25f, 50f, null), result.cpuLoadPercent)
        assertEquals(listOf(75f, null, 100f), result.gpuBusyPercent)
        assertEquals(listOf(30f, null), duplicate.frameRateFps)
        assertEquals(listOf(25f, 50f), duplicate.cpuLoadPercent)
    }

    @Test
    fun `graph accumulator keeps equal samples and bounds history`() {
        val source = PerformanceHudGraphSource("monitor:one", null)
        val accumulator = PerformanceHudGraphAccumulator(capacity = 3)

        accumulator.record(source, 1L, 60.0, 42.0, 24.0)
        accumulator.record(source, 2L, 60.0, 42.0, 24.0)
        accumulator.record(source, 3L, 59.0, 43.0, 25.0)
        val result = accumulator.record(source, 4L, 58.0, 44.0, 26.0)

        assertEquals(listOf(60f, 59f, 58f), result.frameRateFps)
        assertEquals(listOf(42f, 43f, 44f), result.cpuLoadPercent)
        assertEquals(listOf(24f, 25f, 26f), result.gpuBusyPercent)
    }

    @Test
    fun `graph accumulator turns invalid percentages into gaps and resets per stream`() {
        val first = PerformanceHudGraphSource("monitor:one", "com.example.game")
        val second = PerformanceHudGraphSource("auto:7", "com.example.game")
        val accumulator = PerformanceHudGraphAccumulator()

        accumulator.record(first, 10L, Double.NaN, Double.NaN, 101.0)
        val reset = accumulator.record(second, 1L, -1.0, -1.0, 80.0)

        assertEquals(second, reset.source)
        assertEquals(listOf(null), reset.frameRateFps)
        assertEquals(listOf(null), reset.cpuLoadPercent)
        assertEquals(listOf(80f), reset.gpuBusyPercent)
    }

    @Test
    fun `presentation attaches an aligned graph snapshot`() {
        val history = PerformanceHudGraphHistory(
            source = PerformanceHudGraphSource("monitor:one", "com.example.game"),
            frameRateFps = listOf(59f, 60f),
            cpuLoadPercent = listOf(25f, 50f),
            gpuBusyPercent = listOf(75f, null),
        )
        val model = buildPerformanceHudUiModel(
            telemetry = telemetry(),
            autoTune = AdaptiveTuneRuntimeState(),
            autoTunePackageName = null,
            configuredAutoTuneTargetFps = null,
            displayRefreshRateFps = 60,
            effectiveProfileName = "Stock",
            vendorState = PerformanceHudVendorState(),
            graphHistory = history,
        )

        assertEquals(history.frameRateFps, model.frameRateHistoryFps)
        assertEquals(60f, model.frameRateGraphMaximumFps)
        assertEquals(history.cpuLoadPercent, model.cpuLoadHistoryPercent)
        assertEquals(history.gpuBusyPercent, model.gpuBusyHistoryPercent)
    }

    private fun telemetry() = HostAutoTelemetry(
        sequence = 1,
        timestampNanos = 1,
        frameBackend = "surfaceflinger",
        frameConfidencePermille = 900,
        frameLayer = "game",
        frameCount = 120,
        fpsMilli = 59_940,
        frameTimeP95Nanos = 18_000_000,
        slowFrameRatioPermille = 10,
        frameStale = false,
        cpuLoadPermille = listOf(300, 750),
        cpuClockKHz = listOf(900_000L, 2_000_000L),
        gpuBusyPermille = 525,
        gpuClockHz = 600_000_000L,
        thermal = listOf(
            HostThermalReading("soc", 70_000),
            HostThermalReading("battery", 99_000),
        ),
    )
}
