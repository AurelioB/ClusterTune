package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTelemetrySourceTest {
    @Test
    fun `frame target accepts every positive Int and rejects non-positive values`() {
        val layer = "SurfaceView[com.game/com.game.Main](BLAST)#1"
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layer)
                "--latency" in arguments -> success(latency(1_000L, 1_001L))
                else -> error("unexpected command: $arguments")
            }
        }

        listOf(1, Int.MAX_VALUE).forEach { targetFps ->
            val source = source(runner, FakeClock(1_000L))

            assertTrue(source.begin("com.game", targetFps).isSuccess)
            source.end()
        }
        listOf(0, -1, Int.MIN_VALUE).forEach { targetFps ->
            val source = source(runner, FakeClock(1_000L))

            assertTrue(source.begin("com.game", targetFps).isFailure)
        }
    }

    @Test
    fun `huge target keeps latency budget at least one nanosecond`() {
        val layer = "SurfaceView[com.game/com.game.Main](BLAST)#1"
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layer)
                "--latency" in arguments -> success(latency(1_000L, 1_001L))
                else -> error("unexpected command: $arguments")
            }
        }
        val clock = FakeClock(1_000L)
        val source = source(runner, clock)

        source.begin("com.game", Int.MAX_VALUE).getOrThrow()
        clock.now = 1_001L
        val sample = source.sample()

        assertEquals(0, sample.slowFrameRatioPermille)
    }

    @Test
    fun `latency baseline excludes buffered frames from before the session`() {
        val layer = "SurfaceView[com.game/com.game.Main](BLAST)#1"
        val clock = FakeClock(10_000_000_000L)
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layer)
                "--latency" in arguments -> success(latency(8_000_000_000L, 9_000_000_000L))
                else -> error("unexpected command: $arguments")
            }
        }
        val source = source(runner, clock)

        source.begin("com.game", 60).getOrThrow()
        val sample = source.sample()

        assertEquals("surfaceflinger-latency", sample.frameBackend)
        assertEquals(0, sample.frameCount)
        assertTrue(sample.frameStale)
    }

    @Test
    fun `stale latency backend transitions to advancing timestats`() {
        val layer = "SurfaceView[com.game/com.game.Main](BLAST)#1"
        val initialTimeStats = timeStats(
            """
                layerName = $layer
                packageName = com.game
                totalFrames = 10
                totalTimelineFrames = 8
                jankyFrames = 1
            """,
        )
        val updatedTimeStats = timeStats(
            """
                layerName = $layer
                packageName = com.game
                totalFrames = 16
                totalTimelineFrames = 14
                jankyFrames = 2
            """,
        )
        var dumps = 0
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layer)
                "--latency" in arguments -> success(latency(900_000_000L))
                arguments.last() == "-dump" -> success(
                    if (dumps++ == 0) initialTimeStats else updatedTimeStats,
                )
                arguments.last() == "-enable" -> success("")
                else -> error("unexpected command: $arguments")
            }
        }
        val clock = FakeClock(1_000_000_000L)
        val source = source(runner, clock)

        source.begin("com.game", 60).getOrThrow()
        repeat(3) {
            assertEquals("surfaceflinger-latency", source.sample().frameBackend)
        }

        val transition = source.sample()
        assertEquals("surfaceflinger-timestats", transition.frameBackend)
        assertTrue(transition.frameStale)
        assertEquals(1, runner.commands.count { it.last() == "-enable" })

        clock.now = 2_000_000_000L
        val recovered = source.sample()
        assertEquals("surfaceflinger-timestats", recovered.frameBackend)
        assertEquals(6, recovered.frameCount)
        assertEquals(6_000, recovered.fpsMilli)
        assertEquals(166, recovered.timelineJankRatioPermille)
        assertNull(recovered.slowFrameRatioPermille)
        assertFalse(recovered.frameStale)
    }

    @Test
    fun `latency selection probes candidates beyond four before choosing the freshest`() {
        val layers = (1..6).map { index ->
            "SurfaceView[com.game/com.game.View$index](BLAST)#$index"
        }
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layers.joinToString("\n"))
                "--latency" in arguments -> {
                    val index = Regex("View([0-9]+)").find(arguments.last())!!.groupValues[1].toLong()
                    success(latency(index * 100L))
                }
                else -> error("unexpected command: $arguments")
            }
        }
        val source = source(runner, FakeClock(1_000L))

        source.begin("com.game", 60).getOrThrow()

        val probed = runner.commands.filter { "--latency" in it }.map { it.last() }
        assertEquals(layers.toSet(), probed.toSet())
        assertEquals(6, probed.size)
        assertEquals(layers.last(), source.sample().frameLayer)
    }

    @Test
    fun `timestats sessions always re-enable and never disable a global owner`() {
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success("")
                arguments.last() == "-dump" -> success("")
                arguments.last() == "-enable" -> success("")
                else -> error("unexpected command: $arguments")
            }
        }
        val source = source(runner, FakeClock(1_000L))

        source.begin("com.game", 60).getOrThrow()
        source.end()
        source.begin("com.game", 60).getOrThrow()
        source.end()

        assertEquals(2, runner.commands.count { it.last() == "-enable" })
        assertFalse(runner.commands.any { it.last() == "-disable" })
    }

    @Test
    fun `timestats chooses advancing layer and uses timeline delta for jank`() {
        val initial = timeStats(
            """
                layerName = SurfaceView[com.game/com.game.Stale](BLAST)#1
                packageName = com.game
                totalFrames = 1000
                totalTimelineFrames = 900
                jankyFrames = 90
            """,
            """
                displayRefreshRate = 60 fps
                layerName = SurfaceView[com.game/com.game.Game](BLAST)#2
                totalFrames = 60
                totalTimelineFrames = 50
                jankyFrames = 5
            """,
            """
                displayRefreshRate = 120 fps
                layerName = SurfaceView[com.game/com.game.Game](BLAST)#2
                totalFrames = 40
                totalTimelineFrames = 30
                jankyFrames = 3
            """,
        )
        val updated = timeStats(
            """
                layerName = SurfaceView[com.game/com.game.Stale](BLAST)#1
                packageName = com.game
                totalFrames = 1000
                totalTimelineFrames = 900
                jankyFrames = 90
            """,
            """
                displayRefreshRate = 60 fps
                layerName = SurfaceView[com.game/com.game.Game](BLAST)#2
                totalFrames = 90
                totalTimelineFrames = 70
                jankyFrames = 8
            """,
            """
                displayRefreshRate = 120 fps
                layerName = SurfaceView[com.game/com.game.Game](BLAST)#2
                totalFrames = 60
                totalTimelineFrames = 40
                jankyFrames = 6
            """,
        )
        var dumps = 0
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success("")
                arguments.last() == "-dump" -> {
                    val complete = if (dumps++ == 0) initial else updated
                    HostCommandResult(
                        exitCode = 0,
                        stdout = "$complete\n\nlayerName = truncated.partial",
                        truncated = true,
                    )
                }
                arguments.last() == "-enable" -> success("")
                else -> error("unexpected command: $arguments")
            }
        }
        val clock = FakeClock(1_000_000_000L)
        val source = source(runner, clock)

        source.begin("com.game", 60).getOrThrow()
        clock.now = 2_000_000_000L
        val sample = source.sample()

        assertTrue(sample.frameLayer.orEmpty().contains("Game"))
        assertEquals(50, sample.frameCount)
        assertEquals(50_000, sample.fpsMilli)
        assertEquals(200, sample.timelineJankRatioPermille)
        assertNull(sample.slowFrameRatioPermille)
        assertFalse(sample.frameStale)
    }

    @Test
    fun `timestats timeline count is used when total frames is absent`() {
        var dumps = 0
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success("")
                arguments.last() == "-dump" -> success(
                    if (dumps++ == 0) {
                        timeStats(
                            """
                                layerName = com.game/com.game.Main#1
                                totalTimelineFrames = 10
                                totalJankyFrames = 1
                            """,
                        )
                    } else {
                        timeStats(
                            """
                                layerName = com.game/com.game.Main#1
                                totalTimelineFrames = 14
                                totalJankyFrames = 2
                            """,
                        )
                    },
                )
                arguments.last() == "-enable" -> success("")
                else -> error("unexpected command: $arguments")
            }
        }
        val clock = FakeClock(1_000_000_000L)
        val source = source(runner, clock)

        source.begin("com.game", 60).getOrThrow()
        clock.now = 2_000_000_000L
        val sample = source.sample()

        assertEquals(4, sample.frameCount)
        assertEquals(4_000, sample.fpsMilli)
        assertEquals(250, sample.timelineJankRatioPermille)
        assertNull(sample.slowFrameRatioPermille)
    }

    @Test
    fun `kgsl busy values are sampled as direct per-read ratios`() {
        val layer = "com.game/com.game.Main#1"
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success(layer)
                "--latency" in arguments -> success(latency(1_100_000_000L))
                else -> error("unexpected command: $arguments")
            }
        }
        val fs = FakeFs(
            mutableMapOf(
                "/proc/stat" to "cpu 10 0 0 90 0 0 0 0",
                "/sys/class/kgsl/kgsl-3d0/gpubusy" to "150 500",
            ),
        )
        val gpu = GpuDomain(
            id = "kgsl-3d0",
            minPath = null,
            maxPath = "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            curPath = null,
        )
        val source = source(runner, FakeClock(1_100_000_000L), fs, HostCapabilities(emptyList(), gpu))

        source.begin("com.game", 60).getOrThrow()
        val sample = source.sample()

        assertEquals(300, sample.gpuBusyPermille)
    }

    @Test
    fun `45 fps on 60 hz has no slow frames from refresh quantization`() {
        val clock = FakeClock(SECOND)
        val layer = "com.game/com.game.Main#1"
        val frames = (0..90).map { SECOND + kotlin.math.ceil(it * 60.0 / 45).toLong() * SECOND / 60 }
        val source = source(latencyRunner(layer) { latency(*frames.toLongArray()) }, clock)
        source.begin("com.game", 45).getOrThrow()
        clock.now = 3 * SECOND

        val sample = source.sample()
        assertEquals(45_000, sample.fpsMilli)
        assertEquals(0, sample.slowFrameRatioPermille)
        assertTrue(sample.frameWindowComplete)
        assertEquals(1000, sample.frameConfidencePermille)
    }

    @Test
    fun `polls accumulate two seconds beyond the surfaceflinger ring`() {
        val clock = FakeClock(SECOND)
        val runner = latencyRunner("com.game/com.game.Main#1") {
            val frames = (0..((clock.now - SECOND) * 120 / SECOND).toInt())
                .map { SECOND + it * SECOND / 120 }.takeLast(127)
            latency(*frames.toLongArray())
        }
        val source = source(runner, clock)
        source.begin("com.game", 120).getOrThrow()
        clock.now = 2 * SECOND
        assertFalse(source.sample().frameWindowComplete)
        clock.now = 3 * SECOND
        val complete = source.sample()
        assertTrue(complete.frameWindowComplete)
        assertEquals(120_000, complete.fpsMilli)
        assertTrue(complete.frameWindowEndNanos!! - complete.frameWindowStartNanos!! > 1_900_000_000L)

        clock.now = 6 * SECOND
        val overrun = source.sample()
        assertFalse(overrun.frameWindowComplete)
        assertTrue("frame_history_overrun" in overrun.unsupportedMetrics)
    }

    @Test
    fun `newly discovered old frames are stale and future frames are excluded`() {
        val clock = FakeClock(SECOND)
        var frames = listOf(SECOND)
        val source = source(latencyRunner("com.game/com.game.Main#1") { latency(*frames.toLongArray()) }, clock)
        source.begin("com.game", 60).getOrThrow()
        frames = listOf(SECOND + 20_000_000L, SECOND + 40_000_000L, 4 * SECOND)
        clock.now = 3 * SECOND
        val sample = source.sample()
        assertTrue(sample.frameStale)
        assertEquals(2, sample.frameCount)
        assertTrue(sample.frameWindowEndNanos == null || sample.frameWindowEndNanos!! <= clock.now)
    }

    @Test
    fun `verified frequency change excludes earlier presentation history`() {
        val clock = FakeClock(SECOND)
        val runner = latencyRunner("com.game/com.game.Main#1") {
            latency(*(0..((clock.now - SECOND) * 60 / SECOND).toInt()).map { SECOND + it * SECOND / 60 }.toLongArray())
        }
        val source = source(runner, clock)
        source.begin("com.game", 60).getOrThrow()
        clock.now = 3 * SECOND
        val before = source.sample()
        assertTrue(before.frameWindowComplete)
        source.onFrequencyChange()
        clock.now = 4 * SECOND
        val after = source.sample()
        assertFalse(after.frameWindowComplete)
        assertTrue(after.frameWindowStartNanos!! >= 3 * SECOND)
        assertEquals(before.frameMeasurementEpoch, after.frameMeasurementEpoch)
    }

    @Test
    fun `display mode change starts a new measurement epoch`() {
        val clock = FakeClock(SECOND)
        var refresh = 16_666_666L
        val source = source(latencyRunner("com.game/com.game.Main#1") {
            "$refresh\n1 ${clock.now} 1\n"
        }, clock)
        source.begin("com.game", 60).getOrThrow()
        clock.now = 2 * SECOND
        val before = source.sample()
        refresh = 8_333_333L
        clock.now = 3 * SECOND
        val after = source.sample()
        assertTrue(after.frameMeasurementEpoch > before.frameMeasurementEpoch)
        assertFalse(after.frameWindowComplete)
        assertEquals(refresh, after.refreshPeriodNanos)
    }

    @Test
    fun `timestats presentation histogram measures pacing independently of jank`() {
        val clock = FakeClock(SECOND)
        var total = 10
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success("")
                arguments.last() == "-enable" -> success("")
                arguments.last() == "-dump" -> success(timeStats("""
                    layerName = com.game/com.game.Main#1
                    totalFrames = $total
                    totalTimelineFrames = $total
                    jankyFrames = $total
                    present2present histogram is as below:
                    17ms=$total 34ms=0
                """))
                else -> error("unexpected command: $arguments")
            }
        }
        val source = source(runner, clock)
        source.begin("com.game", 60).getOrThrow()
        total = 70
        clock.now = 2 * SECOND
        val sample = source.sample()
        assertEquals(60_000, sample.fpsMilli)
        assertEquals(17_000_000L, sample.frameTimeP95Nanos)
        assertEquals(0, sample.slowFrameRatioPermille)
        assertEquals(1000, sample.timelineJankRatioPermille)
        assertTrue(sample.frameWindowComplete)
        assertEquals(750, sample.frameConfidencePermille)

        total = 1 // External TimeStats reset must not create negative interval counts.
        clock.now = 3 * SECOND
        val reset = source.sample()
        assertNull(reset.frameTimeP95Nanos)
        assertFalse(reset.frameWindowComplete)
    }

    @Test
    fun `future timestamps cannot win layer selection`() {
        val clock = FakeClock(SECOND)
        val active = "SurfaceView[com.game/com.game.Active]#1"
        val future = "SurfaceView[com.game/com.game.Future]#2"
        val runner = RecordingRunner { arguments ->
            when {
                arguments.last() == "--list" -> success("$active\n$future")
                "--latency" in arguments -> success(latency(if (arguments.last() == active) SECOND else 10 * SECOND))
                else -> error("unexpected command: $arguments")
            }
        }
        val source = source(runner, clock)
        source.begin("com.game", 60).getOrThrow()
        assertEquals(active, source.sample().frameLayer)
    }

    @Test
    fun `focused display period overrides the main display latency header`() {
        val clock = FakeClock(SECOND)
        var header = 16_666_666L
        val source = source(latencyRunner("com.game/com.game.Main#1") {
            "$header\n1 ${clock.now} 1\n"
        }, clock)
        source.setDisplayRefreshPeriodNanos(8_333_333L)
        source.begin("com.game", 90).getOrThrow()
        clock.now = 2 * SECOND
        val before = source.sample()
        assertEquals(8_333_333L, before.refreshPeriodNanos)
        header = 33_333_333L
        clock.now = 3 * SECOND
        val after = source.sample()
        assertEquals(8_333_333L, after.refreshPeriodNanos)
        assertEquals(before.frameMeasurementEpoch, after.frameMeasurementEpoch)
        source.end()
        source.begin("com.game", 30).getOrThrow()
        assertEquals(header, source.sample().refreshPeriodNanos)
    }

    private fun latencyRunner(layer: String, dump: () -> String) = RecordingRunner { arguments ->
        when {
            arguments.last() == "--list" -> success(layer)
            "--latency" in arguments -> success(dump())
            else -> error("unexpected command: $arguments")
        }
    }

    private val SECOND = 1_000_000_000L

    private fun source(
        runner: HostCommandRunner,
        clock: HostMonotonicClock,
        fs: HostFilesystem = FakeFs(mutableMapOf("/proc/stat" to "cpu 10 0 0 90 0 0 0 0")),
        capabilities: HostCapabilities = HostCapabilities(emptyList(), null),
    ) = SystemHostTelemetrySource(
        fs = fs,
        hostCapabilities = capabilities,
        runner = runner,
        clock = clock,
        thermalZones = emptyList(),
        dumpsys = "/bin/true",
    )

    private fun success(stdout: String) = HostCommandResult(exitCode = 0, stdout = stdout)

    private fun latency(vararg presented: Long): String = buildString {
        append("16666666\n")
        presented.forEach { timestamp -> append("1 $timestamp 1\n") }
    }

    private fun timeStats(vararg records: String): String =
        records.joinToString("\n\n") { it.trimIndent().trim() }

    private class FakeClock(var now: Long) : HostMonotonicClock {
        override fun nanoTime(): Long = now
    }

    private class RecordingRunner(
        private val response: (List<String>) -> HostCommandResult,
    ) : HostCommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): HostCommandResult {
            commands += arguments
            return response(arguments)
        }
    }

    private class FakeFs(
        private val values: MutableMap<String, String>,
    ) : HostFilesystem {
        override fun read(path: String): String? = values[path]
        override fun write(path: String, value: String): Boolean = false
        override fun mode(path: String): Int? = null
        override fun chmod(path: String, mode: Int): Boolean = false
        override fun exists(path: String): Boolean = path in values
    }
}
