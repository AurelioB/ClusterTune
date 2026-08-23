package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAutoSessionTest {
    @Test
    fun `checkpoint restores exact values and modes with maxima first`() {
        val fs = FakeFs(
            values = mutableMapOf("min" to "200", "max" to "800", "gmin" to "300", "gmax" to "900"),
            modes = mutableMapOf("min" to 416, "max" to 292, "gmin" to 384, "gmax" to 420),
        )
        val checkpointEngine = HostCheckpointEngine(fs, capabilities(withGpu = true))
        val checkpoint = checkpointEngine.capture().getOrThrow()
        fs.values.putAll(mapOf("min" to "100", "max" to "400", "gmin" to "100", "gmax" to "600"))
        fs.modes.keys.forEach { fs.modes[it] = 420 }
        fs.operations.clear()

        val restored = checkpointEngine.restore(checkpoint)

        assertTrue(restored.complete)
        assertEquals(mapOf("min" to "200", "max" to "800", "gmin" to "300", "gmax" to "900"), fs.values)
        assertEquals(mapOf("min" to 416, "max" to 292, "gmin" to 384, "gmax" to 420), fs.modes)
        val lastMaximum = maxOf(fs.operations.indexOf("write:max=800"), fs.operations.indexOf("write:gmax=900"))
        val firstMinimum = minOf(fs.operations.indexOf("write:min=200"), fs.operations.indexOf("write:gmin=300"))
        assertTrue(lastMaximum in 0 until firstMinimum)
    }

    @Test
    fun `session stop is idempotent and restores pre-session cap`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val request = request(cpu = 600)
        assertEquals(HostAutoSessionStatus.ACTIVE, fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request).status)
        assertEquals("600", fixture.fs.values["max"])

        val stopped = fixture.controller.stop(started.sessionId, started.hostEpoch)
        val stoppedAgain = fixture.controller.stop(started.sessionId, started.hostEpoch)

        assertTrue(stopped.restorationComplete)
        assertEquals(stopped, stoppedAgain)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(420, fixture.fs.modes["max"])
    }

    @Test
    fun `new session replaces and restores the previous session`() {
        val fixture = fixture()
        val first = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(first.sessionId!!, first.hostEpoch, request(cpu = 600))

        val second = fixture.controller.start(AutoSessionRequest("com.game", 30, 5_000))

        assertEquals(HostAutoSessionStatus.ACTIVE, second.status)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `heartbeat expiry actively restores when watchdog polls`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))
        fixture.clock.now += 5_000_000_000L

        val expired = fixture.controller.expireIfNeeded()!!

        assertEquals(HostAutoSessionStatus.EXPIRED, expired.status)
        assertTrue(expired.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `automatic steps cannot exceed checkpoint envelope`() {
        val fixture = fixture(withGpu = true)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = started.sessionId!!

        assertTrue(fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 600)).status == HostAutoSessionStatus.ACTIVE)
        assertTrue(fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 900)).status == HostAutoSessionStatus.ACTIVE)
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 1_000, gpu = 900)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 1_000)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 0, gpu = 900, reset = true)) }
    }

    @Test
    fun `host apply engine runs only when cap changes`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = started.sessionId!!

        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        val mutationsAfterFirst = fixture.fs.batchMutations
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))

        assertEquals(mutationsAfterFirst, fixture.fs.batchMutations)
        assertTrue(mutationsAfterFirst > 0)
    }

    @Test
    fun `unknown session is stale and null stop preempts current owner`() {
        val fixture = fixture()
        assertEquals(HostAutoSessionStatus.STOPPED, fixture.controller.stop(null, null).status)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))

        assertEquals(HostAutoSessionStatus.STALE, fixture.controller.heartbeat("wrong", 42L).status)
        assertEquals(HostAutoSessionStatus.STALE, fixture.controller.heartbeat(started.sessionId!!, 41L).status)
        assertEquals(HostAutoSessionStatus.STOPPED, fixture.controller.stop(null, null).status)
    }

    private fun fixture(withGpu: Boolean = false): Fixture {
        val values = mutableMapOf("min" to "200", "max" to "800")
        val modes = mutableMapOf("min" to 416, "max" to 420)
        if (withGpu) {
            values.putAll(mapOf("gmin" to "300", "gmax" to "900"))
            modes.putAll(mapOf("gmin" to 416, "gmax" to 420))
        }
        val fs = FakeFs(values, modes)
        val capabilities = capabilities(withGpu)
        val clock = FakeClock()
        val controller = HostAutoSessionController(
            capabilities,
            fs,
            HostApplyEngine(fs),
            FakeTelemetry(capabilities.cpus.size, withGpu),
            hostEpoch = 42L,
            clock = clock,
        )
        return Fixture(fs, clock, controller)
    }

    private fun capabilities(withGpu: Boolean): HostCapabilities {
        val cpu = CpuDomain(
            id = "policy0",
            minPath = "min",
            maxPath = "max",
            curPath = null,
            minimumCandidates = listOf(200),
            supportedFrequencies = listOf(400, 600, 800),
            stockMax = 800,
            observedMax = 800,
            observedMin = 200,
            selectableMax = 800,
            currentMax = 800,
        )
        val gpu = if (withGpu) GpuDomain(
            id = "gpu0",
            minPath = "gmin",
            maxPath = "gmax",
            curPath = null,
            supportedFrequencies = listOf(300, 600, 900),
            stockMax = 900,
            observedMax = 900,
            observedMin = 300,
            selectableMax = 900,
            currentMax = 900,
        ) else null
        return HostCapabilities(listOf(cpu), gpu)
    }

    private fun request(cpu: Long, gpu: Long? = null, reset: Boolean = false) = ApplyRequest(
        cpuMax = listOf(cpu),
        gpuMax = gpu,
        resetToStock = reset,
        cpuIds = listOf("policy0"),
        gpuId = gpu?.let { "gpu0" },
        gpuMaxPath = gpu?.let { "gmax" },
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("expected IllegalArgumentException", failed)
    }

    private data class Fixture(
        val fs: FakeFs,
        val clock: FakeClock,
        val controller: HostAutoSessionController,
    )

    private class FakeClock(var now: Long = 1_000L) : HostMonotonicClock {
        override fun nanoTime(): Long = now
    }

    private class FakeTelemetry(private val cpuCount: Int, private val hasGpu: Boolean) : HostTelemetrySource {
        override fun capabilities() = HostAutoCapabilities(true, true, false, hasGpu, false, false, "fake")
        override fun begin(packageName: String, targetFps: Int) = Result.success(Unit)
        override fun sample() = HostRawTelemetry(
            timestampNanos = 1L,
            frameBackend = "fake",
            frameConfidencePermille = 1000,
            frameLayer = "layer",
            frameCount = 1,
            fpsMilli = 60_000,
            frameTimeP95Nanos = 17_000_000L,
            slowFrameRatioPermille = 0,
            frameStale = false,
            cpuLoadPermille = List(cpuCount) { 500 },
            cpuClockKHz = List(cpuCount) { null },
            gpuBusyPermille = if (hasGpu) 500 else null,
            gpuClockHz = null,
            thermal = emptyList(),
            unsupportedMetrics = emptyList(),
        )
    }

    private class FakeFs(
        val values: MutableMap<String, String>,
        val modes: MutableMap<String, Int>,
    ) : HostFilesystem {
        val operations = mutableListOf<String>()
        var batchMutations = 0
        override fun read(path: String): String? = values[path]
        override fun write(path: String, value: String): Boolean {
            operations += "write:$path=$value"
            values[path] = value
            return true
        }
        override fun mode(path: String): Int? = modes[path]
        override fun chmod(path: String, mode: Int): Boolean {
            operations += "chmod:$path=$mode"
            modes[path] = mode
            return true
        }
        override fun exists(path: String): Boolean = path in values
        override fun mutate(operations: List<HostMutation>): Boolean {
            batchMutations++
            return super<HostFilesystem>.mutate(operations)
        }
    }
}
