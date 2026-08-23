package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTelemetryParserTest {
    @Test
    fun `surface layer matching is bounded deterministic and package exact`() {
        val text = """
            com.game.other/com.game.other.Main#1
            com.game/com.game.Main#2
            SurfaceView[com.game/com.game.Main](BLAST)#3
            com.game.injected/com.game.injected.Main#4
        """.trimIndent()

        val layers = HostTelemetryParsers.surfaceLayers(text, "com.game")

        assertEquals("SurfaceView[com.game/com.game.Main](BLAST)#3", layers.first())
        assertEquals(2, layers.size)
        assertFalse(layers.any { "injected" in it || "other" in it })
        assertTrue(HostTelemetryParsers.surfaceLayers(text, "com.game;id").isEmpty())
    }

    @Test
    fun `surface latency ignores sentinels and sorts presented timestamps`() {
        val parsed = HostTelemetryParsers.surfaceLatency(
            "16666666\n1 300 4\n1 100 4\n0 0 0\n1 ${Long.MAX_VALUE} 4\n1 300 5\n",
        )

        assertEquals(16_666_666L, parsed.refreshPeriodNanos)
        assertEquals(listOf(100L, 300L), parsed.presentedNanos)
    }

    @Test
    fun `frame history is limited to rolling two second window`() {
        val latest = 10_000_000_000L
        val values = listOf(latest - 3_000_000_000L, latest - 2_000_000_000L, latest - 1_000_000_000L, latest)

        assertEquals(values.drop(1), HostTelemetryParsers.rollingPresentedNanos(values))
    }

    @Test
    fun `timestats parser tolerates reordered fields and matches package in layer name`() {
        val text = """
            unknownFutureField = ignored
            averageFPS = 59.75
            layerName = SurfaceView[com.game/com.game.Main](BLAST)#3
            jankyFrames = 4 (2.0%)
            totalFrames = 200

            packageName = com.other
            totalFrames = 999
            layerName = com.other/com.other.Main#1
            averageFPS = 120
        """.trimIndent()

        val layer = HostTelemetryParsers.matchingTimeStatsLayer(text, "com.game")!!

        assertEquals("SurfaceView[com.game/com.game.Main](BLAST)#3", layer.layerName)
        assertEquals(59_750, layer.averageFpsMilli)
        assertEquals(200L, layer.totalFrames)
        assertEquals(4L, layer.jankyFrames)
    }

    @Test
    fun `proc stat provides aggregate and per cpu deltas`() {
        val before = HostTelemetryParsers.procStat("cpu 10 0 10 80 0 0 0 0\ncpu0 5 0 5 40 0 0 0 0\n")
        val after = HostTelemetryParsers.procStat("cpu 20 0 20 160 0 0 0 0\ncpu0 10 0 10 80 0 0 0 0\n")

        assertEquals(200, HostTelemetryParsers.cpuLoadPermille(before[null], after[null]))
        assertEquals(200, HostTelemetryParsers.cpuLoadPermille(before[0], after[0]))
        assertNull(HostTelemetryParsers.cpuLoadPermille(after[0], before[0]))
        assertEquals(listOf(0, 1, 2, 4), HostTelemetryParsers.cpuList("0-2,4"))
    }

    @Test
    fun `gpu busy supports instantaneous and cumulative formats`() {
        assertEquals(420, HostTelemetryParsers.gpuBusyPermille(null, HostTelemetryParsers.gpuBusy("42%", true)))
        val before = HostTelemetryParsers.gpuBusy("100 400")
        val after = HostTelemetryParsers.gpuBusy("150 500")
        assertEquals(500, HostTelemetryParsers.gpuBusyPermille(before, after))
    }

    @Test
    fun `thermal values normalize degrees and reject implausible readings`() {
        assertEquals(42_000L, HostTelemetryParsers.thermalMilliCelsius("42"))
        assertEquals(42_000L, HostTelemetryParsers.thermalMilliCelsius("420"))
        assertEquals(42_000L, HostTelemetryParsers.thermalMilliCelsius("42000"))
        assertNull(HostTelemetryParsers.thermalMilliCelsius("999999"))
    }
}
