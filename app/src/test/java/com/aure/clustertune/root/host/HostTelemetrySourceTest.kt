package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTelemetrySourceTest {
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
        assertEquals(166, recovered.slowFrameRatioPermille)
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
        assertEquals(200, sample.slowFrameRatioPermille)
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
        assertEquals(250, sample.slowFrameRatioPermille)
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
        val source = source(runner, FakeClock(1_000_000_000L), fs, HostCapabilities(emptyList(), gpu))

        source.begin("com.game", 60).getOrThrow()
        val sample = source.sample()

        assertEquals(300, sample.gpuBusyPermille)
    }

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
