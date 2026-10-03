package com.aure.clustertune.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceHudOverlayTest {

    @Test
    fun `formatters preserve telemetry units and use compact values`() {
        assertEquals("59.9", formatHudDecimal(59.94))
        assertEquals("48%", formatHudPercent(47.6))
        assertEquals("2.85 GHz", formatHudCpuClock(2_850_000L))
        assertEquals("806 MHz", formatHudCpuClock(806_400L))
        assertEquals("1.10 GHz", formatHudGpuClock(1_100_000_000L))
        assertEquals("680 MHz", formatHudGpuClock(680_000_000L))
        assertEquals("72°C", formatHudTemperature(71.6))
    }

    @Test
    fun `telemetry rows combine related metrics and omit unsupported values`() {
        val rows = performanceHudTelemetryRows(
            PerformanceHudUiModel(
                framesPerSecond = 59.94,
                p95FrameTimeMillis = 18.24,
                cpuLoadPercent = 47.6,
                cpuClockKHz = 2_850_000L,
                gpuBusyPercent = Double.NaN,
                gpuClockHz = 680_000_000L,
                maxTemperatureCelsius = -1.0,
            ),
        )

        assertEquals(
            listOf(
                "FRAME" to "59.9 FPS · P95 18.2 ms",
                "CPU" to "48% · 2.85 GHz",
                "GPU" to "680 MHz",
            ),
            rows.map { it.label to it.value },
        )
    }

    @Test
    fun `empty invalid telemetry produces no telemetry rows`() {
        val rows = performanceHudTelemetryRows(
            PerformanceHudUiModel(
                framesPerSecond = Double.NaN,
                p95FrameTimeMillis = -2.0,
                cpuLoadPercent = 101.0,
                cpuClockKHz = 0L,
                gpuBusyPercent = -1.0,
                gpuClockHz = -1L,
                maxTemperatureCelsius = Double.POSITIVE_INFINITY,
            ),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `frame history remains visible while the current sample is unavailable`() {
        val rows = performanceHudTelemetryRows(
            PerformanceHudUiModel(
                frameRateHistoryFps = listOf(58f, null),
                frameRateGraphMaximumFps = 60f,
            ),
        )

        assertEquals(listOf("FRAME" to "—"), rows.map { it.label to it.value })
    }

    @Test
    fun `context rows trim labels and present exactly one ClusterTune mode`() {
        val fixedRows = performanceHudContextRows(
            PerformanceHudUiModel(
                oemPerformanceProfile = "  Performance  ",
                fanProfile = " Turbo ",
                tuneMode = PerformanceHudTuneMode.FixedProfile(" Balanced "),
            ),
        )
        assertEquals(
            listOf(
                "OEM" to "Performance",
                "FAN" to "Turbo",
                "PROFILE" to "Balanced",
            ),
            fixedRows.map { it.label to it.value },
        )

        val autoRows = performanceHudContextRows(
            PerformanceHudUiModel(
                tuneMode = PerformanceHudTuneMode.AutoTune(
                    packageName = "com.example.game",
                    targetFps = 144,
                    targetRange = 30..120,
                ),
            ),
        )
        assertEquals(listOf("AUTO" to "120 FPS"), autoRows.map { it.label to it.value })
    }

    @Test
    fun `auto target override is bounded by model supplied range`() {
        val model = PerformanceHudUiModel(
            tuneMode = PerformanceHudTuneMode.AutoTune(
                packageName = "com.example.game",
                targetFps = 60,
                targetRange = 120..30,
            ),
        )

        assertEquals("30 FPS", performanceHudContextRows(model, 1).single().value)
        assertEquals("77 FPS", performanceHudContextRows(model, 77).single().value)
        assertEquals("120 FPS", performanceHudContextRows(model, 200).single().value)
    }

    @Test
    fun `graph samples retain the newest thirty values on a fixed percent scale`() {
        val samples = List(34) { index -> index.toFloat() } +
            listOf(-1f, Float.NaN, 125f)

        val bounded = performanceHudGraphSamples(samples)

        assertEquals(PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT, bounded.size)
        assertEquals(7f, bounded.first())
        assertEquals(null, bounded[27])
        assertEquals(null, bounded[28])
        assertEquals(100f, bounded.last())
    }

    @Test
    fun `fps graph samples use refresh ceiling and retain gaps`() {
        val samples = performanceHudFpsGraphSamples(
            samples = listOf(30f, null, 60f, 72f, -1f, Float.NaN),
            maximumFps = 60f,
        )

        assertEquals(listOf(50f, null, 100f, 100f, null, null), samples)
    }

    @Test
    fun `fps graph is omitted without a valid ceiling`() {
        assertTrue(performanceHudFpsGraphSamples(listOf(30f, 60f), null).isEmpty())
        assertTrue(performanceHudFpsGraphSamples(listOf(30f, 60f), 0f).isEmpty())
        assertTrue(performanceHudFpsGraphSamples(listOf(30f, 60f), Float.NaN).isEmpty())
    }

    @Test
    fun `missing graph samples split rather than bridge history`() {
        val samples = performanceHudGraphSamples(listOf(10f, 20f, null, 40f, 50f, null))

        val segments = performanceHudGraphSegments(samples)

        assertEquals(listOf(listOf(0, 1), listOf(3, 4)), segments.map { segment ->
            segment.map(PerformanceHudGraphPoint::sampleIndex)
        })
    }
}
