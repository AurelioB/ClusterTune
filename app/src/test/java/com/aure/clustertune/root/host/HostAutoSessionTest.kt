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
    fun `late session stop cannot overwrite a manual profile applied after preemption`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))

        val preempted = requireNotNull(fixture.controller.stopCurrent("manual profile preemption"))
        assertTrue(preempted.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])

        HostApplyEngine(fixture.fs).applyOrThrow(capabilities(withGpu = false), request(cpu = 400))
        assertEquals("400", fixture.fs.values["max"])

        val lateStop = fixture.controller.stop(started.sessionId, started.hostEpoch)

        assertEquals(preempted, lateStop)
        assertEquals("400", fixture.fs.values["max"])
    }

    @Test
    fun `failed restoration stays latched and explicit stop retries the original checkpoint`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextWrites("max", count = 1)

        val failed = fixture.controller.stop(started.sessionId, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals(failed, fixture.controller.current())
        assertEquals("600", fixture.fs.values["max"])

        val restored = fixture.controller.stop(started.sessionId, started.hostEpoch)
        val idempotent = fixture.controller.stop(started.sessionId, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(restored, idempotent)
    }

    @Test
    fun `replacement cannot checkpoint partially restored hardware`() {
        val fixture = fixture()
        val first = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(first.sessionId!!, first.hostEpoch, request(cpu = 600))
        fixture.fs.failNextWrites("max", count = 2)
        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, fixture.controller.stop(first.sessionId, first.hostEpoch).status)

        val blocked = fixture.controller.start(AutoSessionRequest("com.other", 30, 5_000))

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, blocked.status)
        assertEquals(first.sessionId, blocked.sessionId)
        assertEquals(1, fixture.telemetry.beginCount)
        assertEquals("600", fixture.fs.values["max"])

        val replacement = fixture.controller.start(AutoSessionRequest("com.other", 30, 5_000))
        assertEquals(HostAutoSessionStatus.ACTIVE, replacement.status)
        assertNotEquals(first.sessionId, replacement.sessionId)
        assertEquals(2, fixture.telemetry.beginCount)
        assertEquals("800", fixture.fs.values["max"])

        fixture.controller.applyStep(replacement.sessionId!!, replacement.hostEpoch, request(cpu = 600))
        assertTrue(fixture.controller.stop(replacement.sessionId, replacement.hostEpoch).restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `profile preemption remains blocked until pending restoration succeeds`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextWrites("max", count = 2)
        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, fixture.controller.stop(started.sessionId, started.hostEpoch).status)

        val stillBlocked = requireNotNull(fixture.controller.stopCurrent("profile apply preemption"))

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, stillBlocked.status)
        assertFalse(stillBlocked.restorationComplete)
        assertEquals("600", fixture.fs.values["max"])

        val restored = requireNotNull(fixture.controller.stopCurrent("profile apply preemption"))
        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
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
    fun `session targets accept every positive Int and reject non-positive values`() {
        listOf(1, Int.MAX_VALUE).forEach { targetFps ->
            val fixture = fixture()
            val started = fixture.controller.start(AutoSessionRequest("com.game", targetFps, 5_000))

            assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
            assertEquals(targetFps, started.targetFps)
            assertEquals(listOf(targetFps), fixture.telemetry.begunTargets)
        }

        listOf(0, -1, Int.MIN_VALUE).forEach { targetFps ->
            val fixture = fixture()

            assertFails {
                fixture.controller.start(AutoSessionRequest("com.game", targetFps, 5_000))
            }
            assertEquals(0, fixture.telemetry.beginCount)
        }
    }

    @Test
    fun `snapshot target keeps zero sentinel only outside active sessions`() {
        assertEquals(
            0,
            HostAutoSessionSnapshot(
                sessionId = null,
                hostEpoch = 42L,
                status = HostAutoSessionStatus.STOPPED,
                targetFps = 0,
            ).targetFps,
        )
        assertEquals(
            Int.MAX_VALUE,
            HostAutoSessionSnapshot(
                sessionId = "session",
                hostEpoch = 42L,
                status = HostAutoSessionStatus.ACTIVE,
                targetFps = Int.MAX_VALUE,
            ).targetFps,
        )
        assertFails {
            HostAutoSessionSnapshot(
                sessionId = "session",
                hostEpoch = 42L,
                status = HostAutoSessionStatus.ACTIVE,
                targetFps = 0,
            )
        }
        assertFails {
            HostAutoSessionSnapshot(
                sessionId = null,
                hostEpoch = 42L,
                status = HostAutoSessionStatus.STOPPED,
                targetFps = -1,
            )
        }
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
    fun `heartbeat preserves tuning when an OEM minimum rises above the active cap`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.values["min"] = "700"

        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
        assertFalse(stopped.restorationAttempted)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals("700", fixture.fs.values["min"])
    }

    @Test
    fun `telemetry read preserves tuning when an OEM minimum falls`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.values["min"] = "100"

        val stopped = fixture.controller.readTelemetry(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
        assertFalse(stopped.restorationAttempted)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals("100", fixture.fs.values["min"])
    }

    @Test
    fun `automatic apply remains maximum-only when a live minimum becomes unreadable`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextReads("min", count = 1)
        val operationsBefore = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400))

        assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
        assertFalse(stopped.restorationAttempted)
        assertFalse(fixture.fs.operations.drop(operationsBefore).any { it.startsWith("write:min=") })
        assertEquals("400", fixture.fs.values["max"])
    }

    @Test
    fun `minimum change racing an automatic apply leaves the OEM vote untouched`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        // The OEM changes its minimum while the max-only transaction is starting.
        fixture.fs.rewriteAfterReads("min", "400", reads = 1)

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400))

        assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
        assertFalse(stopped.restorationAttempted)
        assertTrue(fixture.fs.operations.any { it == "write:max=400" })
        assertEquals("400", fixture.fs.values["max"])
        assertEquals("400", fixture.fs.values["min"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") })
    }

    @Test
    fun `OEM minimum votes on fixed CPU and GPU do not interrupt max-only tuning or restoration`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.values["min0"] = "800"
        fixture.fs.values["min1"] = "1200"
        fixture.fs.values["gmin"] = "900"
        val applied = fixture.controller.applyStep(
            session,
            started.hostEpoch,
            ApplyRequest(
                cpuMax = listOf(800, 900),
                gpuMax = 600,
                resetToStock = false,
                cpuIds = listOf("policy0", "policy4"),
                gpuId = "gpu0",
                gpuMaxPath = "gmax",
            ),
        )
        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("900", fixture.fs.values["max1"])
        assertEquals("600", fixture.fs.values["gmax"])
        assertEquals(HostAutoSessionStatus.ACTIVE, fixture.controller.readTelemetry(session, started.hostEpoch).status)
        assertTrue(fixture.controller.stop(session, started.hostEpoch).restorationComplete)
        assertEquals("800", fixture.fs.values["min0"])
        assertEquals("1200", fixture.fs.values["min1"])
        assertEquals("900", fixture.fs.values["gmin"])
        assertFalse(fixture.fs.operations.any {
            it.startsWith("write:min") || it.startsWith("write:gmin") ||
                it.startsWith("chmod:min") || it.startsWith("chmod:gmin")
        })
    }

    @Test
    fun `automatic steps cannot exceed checkpoint envelope`() {
        val fixture = fixture(withGpu = true)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = started.sessionId!!

        val trimmed = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 600))
        assertEquals(trimmed.toString(), HostAutoSessionStatus.ACTIVE, trimmed.status)
        val recovered = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 900))
        assertEquals(recovered.toString(), HostAutoSessionStatus.ACTIVE, recovered.status)
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 1_000, gpu = 900)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 1_000)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 0, gpu = 900, reset = true)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800)) }
        assertFails {
            fixture.controller.applyStep(
                session,
                started.hostEpoch,
                request(cpu = 800, gpu = 900).copy(cpuIds = emptyList()),
            )
        }
    }

    @Test
    fun `automatic steps reject CPU and GPU targets at or below positive live minimums before mutation`() {
        val fixture = fixture(withGpu = true, cpuMin = 600, gpuMin = 600)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val operationsBefore = fixture.fs.operations.toList()
        val batchesBefore = fixture.fs.batchMutations

        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 900)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400, gpu = 900)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 600)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 300)) }

        assertEquals(operationsBefore, fixture.fs.operations)
        assertEquals(batchesBefore, fixture.fs.batchMutations)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(HostAutoSessionStatus.ACTIVE, fixture.controller.current().status)
    }

    @Test
    fun `automatic steps accept the first supported OPP strictly above each live minimum`() {
        val fixture = fixture(withGpu = true, cpuMin = 450, gpuMin = 450)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        val applied = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 600))

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals("600", fixture.fs.values["gmax"])
        assertEquals("450", fixture.fs.values["min"])
        assertEquals("450", fixture.fs.values["gmin"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") || it.startsWith("write:gmin=") })
        assertFalse(fixture.fs.operations.any { it.startsWith("chmod:min=") || it.startsWith("chmod:gmin=") })
    }

    @Test
    fun `empty host OPP lists accept app fallback targets strictly above live minimums`() {
        val fs = FakeFs(
            values = mutableMapOf("min" to "200", "max" to "800", "gmin" to "300", "gmax" to "900"),
            modes = mutableMapOf("min" to 416, "max" to 420, "gmin" to 416, "gmax" to 420),
        )
        val base = capabilities(withGpu = true)
        val capabilities = base.copy(
            cpus = base.cpus.map { it.copy(supportedFrequencies = emptyList()) },
            gpu = requireNotNull(base.gpu).copy(supportedFrequencies = emptyList()),
        )
        val controller = HostAutoSessionController(
            capabilities,
            fs,
            HostApplyEngine(fs),
            FakeTelemetry(capabilities.cpus.size, hasGpu = true),
            hostEpoch = 42L,
            clock = FakeClock(),
        )
        val started = controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        val applied = controller.applyStep(session, started.hostEpoch, request(cpu = 201, gpu = 301))

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("201", fs.values["max"])
        assertEquals("301", fs.values["gmax"])
        assertEquals("200", fs.values["min"])
        assertEquals("300", fs.values["gmin"])
    }

    @Test
    fun `pinned domains with no supported step above the live minimum can only stay at base`() {
        val fixture = fixture(withGpu = true, cpuMin = 800, gpuMin = 900)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val operationsBefore = fixture.fs.operations.toList()
        val batchesBefore = fixture.fs.batchMutations

        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 900)) }
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 600)) }
        val unchanged = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 900))

        assertEquals(HostAutoSessionStatus.ACTIVE, unchanged.status)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(operationsBefore, fixture.fs.operations)
        assertEquals(batchesBefore, fixture.fs.batchMutations)
    }

    @Test
    fun `pathless KGSL minimum is derived dynamically from the raw positional frequency vector`() {
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        val gpuMaxPath = "$kgsl/max_gpuclk"
        val fs = FakeFs(
            values = mutableMapOf(
                "min" to "200",
                "max" to "800",
                gpuMaxPath to "900",
                "$kgsl/min_pwrlevel" to "2",
                // Index 2 must remain 600; de-duplicating first would incorrectly select 300.
                "$kgsl/gpu_available_frequencies" to "900 600 600 300",
            ),
            modes = mutableMapOf("min" to 416, "max" to 420, gpuMaxPath to 420),
        )
        val base = capabilities(withGpu = true)
        val capabilities = base.copy(
            gpu = requireNotNull(base.gpu).copy(
                id = "kgsl-3d0",
                minPath = null,
                maxPath = gpuMaxPath,
                observedMin = 300,
            ),
        )
        val controller = HostAutoSessionController(
            capabilities,
            fs,
            HostApplyEngine(fs),
            FakeTelemetry(capabilities.cpus.size, hasGpu = true),
            hostEpoch = 42L,
            clock = FakeClock(),
        )
        val started = controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val operationsBefore = fs.operations.toList()
        val batchesBefore = fs.batchMutations
        fun step(cpu: Long, gpu: Long) = ApplyRequest(
            cpuMax = listOf(cpu),
            gpuMax = gpu,
            resetToStock = false,
            cpuIds = listOf("policy0"),
            gpuId = "kgsl-3d0",
            gpuMaxPath = gpuMaxPath,
        )

        assertEquals(600L, started.state?.gpuMin)
        assertFails { controller.applyStep(session, started.hostEpoch, step(cpu = 800, gpu = 600)) }
        assertEquals(operationsBefore, fs.operations)
        assertEquals(batchesBefore, fs.batchMutations)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            controller.applyStep(session, started.hostEpoch, step(cpu = 600, gpu = 900)).status,
        )

        fs.values["$kgsl/min_pwrlevel"] = "3"
        val stopped = controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
        assertFalse(stopped.restorationAttempted)
        assertEquals("600", fs.values["max"])
        assertEquals("900", fs.values[gpuMaxPath])
    }

    @Test
    fun `pathless KGSL minimum rejects a raw vector containing any invalid frequency`() {
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        val gpuMaxPath = "$kgsl/max_gpuclk"
        val frequenciesPath = "$kgsl/gpu_available_frequencies"
        val fs = FakeFs(
            values = mutableMapOf(
                "min" to "200",
                "max" to "800",
                gpuMaxPath to "900",
                "$kgsl/min_pwrlevel" to "0",
                frequenciesPath to "900 600 300",
            ),
            modes = mutableMapOf("min" to 416, "max" to 420, gpuMaxPath to 420),
        )
        val base = capabilities(withGpu = true)
        val capabilities = base.copy(
            gpu = requireNotNull(base.gpu).copy(
                id = "kgsl-3d0",
                minPath = null,
                maxPath = gpuMaxPath,
            ),
        )

        listOf(
            "900 invalid 300",
            "900 0 300",
            "900 -600 300",
        ).forEach { rawFrequencies ->
            fs.values[frequenciesPath] = rawFrequencies

            assertEquals(null, HostHardwareStateReader.read(fs, capabilities).gpuMin)
        }
    }

    @Test
    fun `atomic baseline remains valid when live minimums make automatic domains inert`() {
        val fixture = fixture(withGpu = true, cpuMin = 700, gpuMin = 700)
        fixture.fs.values["max"] = "400"
        fixture.fs.values["gmax"] = "600"
        val baseline = request(cpu = 800, gpu = 900).copy(maximumsOnly = true)

        val started = fixture.controller.start(
            AutoSessionRequest("com.game", 60, 5_000, baseline = baseline),
        )

        assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
        assertEquals(listOf(800L), started.state?.cpuMax)
        assertEquals(900L, started.state?.gpuMax)
        assertEquals("700", fixture.fs.values["min"])
        assertEquals("700", fixture.fs.values["gmin"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") || it.startsWith("write:gmin=") })
        assertFalse(fixture.fs.operations.any { it.startsWith("chmod:min=") || it.startsWith("chmod:gmin=") })

        val session = requireNotNull(started.sessionId)
        val operationsAfterBaseline = fixture.fs.operations.toList()
        val batchesAfterBaseline = fixture.fs.batchMutations
        assertFails { fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 600)) }
        assertEquals(operationsAfterBaseline, fixture.fs.operations)
        assertEquals(batchesAfterBaseline, fixture.fs.batchMutations)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals("900", fixture.fs.values["gmax"])

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals("700", fixture.fs.values["min"])
        assertEquals("700", fixture.fs.values["gmin"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") || it.startsWith("write:gmin=") })
        assertFalse(fixture.fs.operations.any { it.startsWith("chmod:min=") || it.startsWith("chmod:gmin=") })
    }

    @Test
    fun `baseline applies with unreadable minima without claiming minimum nodes`() {
        val fixture = fixture(withGpu = true)
        fixture.fs.values.remove("min")
        fixture.fs.values.remove("gmin")
        val started = fixture.controller.start(
            AutoSessionRequest(
                "com.game", 60, 5_000,
                baseline = request(cpu = 600, gpu = 600).copy(maximumsOnly = true),
            ),
        )
        assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals("600", fixture.fs.values["gmax"])
        assertFails {
            fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 400, gpu = 600))
        }
        assertTrue(fixture.controller.stop(started.sessionId, started.hostEpoch).restorationComplete)
        assertEquals(null, fixture.fs.values["min"])
        assertEquals(null, fixture.fs.values["gmin"])
        assertFalse(fixture.fs.operations.any {
            it.startsWith("write:min") || it.startsWith("write:gmin") ||
                it.startsWith("chmod:min") || it.startsWith("chmod:gmin")
        })
    }

    @Test
    fun `CPU and GPU baseline may equal or fall below OEM minimum votes`() {
        listOf(600L, 700L).forEach { minimum ->
            val fixture = fixture(withGpu = true, cpuMin = minimum, gpuMin = minimum)
            val started = fixture.controller.start(
                AutoSessionRequest(
                    "com.game", 60, 5_000,
                    baseline = request(cpu = 600, gpu = 600).copy(maximumsOnly = true),
                ),
            )
            assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
            assertEquals("600", fixture.fs.values["max"])
            assertEquals("600", fixture.fs.values["gmax"])
            assertFails {
                fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 400, gpu = 600))
            }
            assertTrue(fixture.controller.stop(started.sessionId, started.hostEpoch).restorationComplete)
            assertEquals(minimum.toString(), fixture.fs.values["min"])
            assertEquals(minimum.toString(), fixture.fs.values["gmin"])
            assertFalse(fixture.fs.operations.any {
                it.startsWith("write:min") || it.startsWith("write:gmin") ||
                    it.startsWith("chmod:min") || it.startsWith("chmod:gmin")
            })
        }
    }

    @Test
    fun `indeterminate atomic baseline restores every changed ceiling and latches a failed restore`() {
        val fixture = multiDomainFixture()
        fixture.fs.failNextMutationIndeterminately(afterOperations = 6)
        fixture.fs.failNextWriteValue("max0", "800", count = 1)

        val failed = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = multiRequest().copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals("600", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertTrue(fixture.fs.operations.contains("write:max0=600"))
        assertTrue(fixture.fs.operations.contains("write:max1=900"))
        assertTrue(fixture.fs.operations.contains("write:max1=1200"))

        val restored = fixture.controller.stop(failed.sessionId, failed.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `OEM rewrite after atomic baseline preserves drifted domain and restores other domains`() {
        val fixture = multiDomainFixture()
        fixture.fs.rewriteAfterNextBatchRead("max0", "700")

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = multiRequest().copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("baseline failed"))
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(listOf("write:max0=600"), fixture.fs.operations.filter { it.startsWith("write:max0=") })
        assertTrue(fixture.fs.operations.contains("write:max1=1200"))
        assertTrue(fixture.fs.operations.contains("write:gmax=900"))
    }

    @Test
    fun `telemetry begin failure performs no atomic baseline writes`() {
        val fixture = fixture(telemetryBeginFailure = IllegalStateException("fake telemetry failure"))
        fixture.fs.values["max"] = "400"
        val operationsBeforeStart = fixture.fs.operations.toList()
        val mutationsBeforeStart = fixture.fs.batchMutations

        val unsupported = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = request(cpu = 800).copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.UNSUPPORTED, unsupported.status)
        assertTrue(unsupported.restorationComplete)
        assertTrue(unsupported.message.orEmpty().contains("fake telemetry failure"))
        assertEquals("400", fixture.fs.values["max"])
        assertEquals(operationsBeforeStart, fixture.fs.operations)
        assertEquals(mutationsBeforeStart, fixture.fs.batchMutations)
        assertEquals(1, fixture.telemetry.beginCount)
    }

    @Test
    fun `OEM ceiling change during telemetry startup permits baseline and restores it after tuning`() {
        val fixture = fixture()
        fixture.telemetry.onBegin = { fixture.fs.values["max"] = "700" }
        val started = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = request(cpu = 800).copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
        assertEquals("800", fixture.fs.values["max"])
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))
        val stopped = fixture.controller.stop(started.sessionId, started.hostEpoch)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `session without baseline captures OEM ceiling after telemetry startup`() {
        val fixture = fixture()
        fixture.telemetry.onBegin = { fixture.fs.values["max"] = "700" }
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))

        assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
        fixture.controller.applyStep(started.sessionId!!, started.hostEpoch, request(cpu = 600))
        val stopped = fixture.controller.stop(started.sessionId, started.hostEpoch)
        assertTrue(stopped.restorationComplete)
        assertEquals("700", fixture.fs.values["max"])
    }

    @Test
    fun `checkpoint failure after telemetry startup closes telemetry without writes`() {
        val fixture = fixture()
        fixture.telemetry.onBegin = { fixture.fs.values.remove("max") }
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))

        assertEquals(HostAutoSessionStatus.UNSUPPORTED, started.status)
        assertEquals(1, fixture.telemetry.endCount)
        assertTrue(fixture.fs.operations.isEmpty())
    }

    @Test
    fun `failed CPU-only baseline never mutates untouched GPU state or mode`() {
        val fixture = fixture(withGpu = true)
        fixture.fs.failNextMutationIndeterminately(afterOperations = 2)
        val operationsBeforeStart = fixture.fs.operations.size

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = request(cpu = 600).copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(420, fixture.fs.modes["gmax"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStart).any {
                it.startsWith("write:gmax=") || it.startsWith("chmod:gmax=")
            },
        )
    }

    @Test
    fun `hidden stock rollback fallback remains owned until exact startup recovery`() {
        val fixture = hiddenStockFixture()
        val fs = fixture.fs
        val controller = fixture.controller
        // The second-domain failure triggers rollback. The first exact hidden-stock restore
        // fails once and HostApplyEngine safely falls back to the selectable ceiling (800).
        // Startup recovery must still recognize 800 as ours and retry the exact 1000 checkpoint.
        fs.failNextWrites("max1", count = 1)
        fs.failNextWriteValue("max0", "1000", count = 1)

        val stopped = controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(400, 900),
                    gpuMax = null,
                    resetToStock = false,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("1000", fs.values["max0"])
        assertTrue(fs.operations.contains("write:max0=800"))
        assertEquals(2, fs.operations.count { it == "write:max0=1000" })
    }

    @Test
    fun `successful baseline does not claim an external selectable fallback`() {
        val fixture = hiddenStockFixture()
        // The baseline transaction writes 400 and verifies it. Before the host can capture
        // the final baseline, an external policy selects the device's 800 ceiling.
        fixture.fs.rewriteAfterNextBatchRead("max0", "800")

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(400, 900),
                    gpuMax = null,
                    resetToStock = false,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertFalse(fixture.fs.operations.any { it == "write:max0=1000" })
    }

    @Test
    fun `indeterminate baseline does not claim an external selectable fallback`() {
        val fixture = hiddenStockFixture()
        fixture.fs.failNextMutationIndeterminately(afterOperations = 1)
        fixture.fs.rewriteOnNextIndeterminateMutation("max0", "800")

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(400, 900),
                    gpuMax = null,
                    resetToStock = false,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertFalse(fixture.fs.operations.any { it == "write:max0=1000" })
    }

    @Test
    fun `indeterminate baseline does not claim alias or mode of an exact no-op domain`() {
        val fixture = hiddenStockFixture()
        fixture.fs.failNextMutationIndeterminately(afterOperations = 1)
        fixture.fs.rewriteOnNextIndeterminateMutation("max0", "800", mode = 292)

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(1000, 900),
                    gpuMax = null,
                    resetToStock = false,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals(292, fixture.fs.modes["max0"])
        val externalRewrite = fixture.fs.operations.indexOf("external:max0=800 mode=292")
        assertTrue(externalRewrite >= 0)
        assertFalse(
            fixture.fs.operations.drop(externalRewrite + 1).any {
                it.startsWith("write:max0=") || it.startsWith("chmod:max0=")
            },
        )
    }

    @Test
    fun `indeterminate baseline chmod-only domain does not claim an OEM alias value`() {
        val fixture = hiddenStockFixture()
        fixture.fs.modes["max0"] = 292
        fixture.fs.failNextMutationIndeterminately(afterOperations = 2)
        fixture.fs.rewriteOnNextIndeterminateMutation("max0", "800", mode = 384)

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(1000, 900),
                    gpuMax = null,
                    resetToStock = false,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals(384, fixture.fs.modes["max0"])
        val externalRewrite = fixture.fs.operations.indexOf("external:max0=800 mode=384")
        assertTrue(externalRewrite >= 0)
        assertFalse(
            fixture.fs.operations.drop(externalRewrite + 1).any { it.startsWith("write:max0=") },
        )
    }

    @Test
    fun `confirmed stock CPU baseline does not claim a later external fallback`() {
        val fixture = hiddenStockFixture()
        fixture.fs.rewriteAfterReads(
            path = "max0",
            value = "800",
            // The stock baseline is now an exact max-only no-op. Trigger the OEM
            // takeover after its verification and baseline-state reads without relying
            // on a mutation batch that correctly never happens.
            reads = 5,
            failNextModeAfterRewrite = true,
        )

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(1000, 1200),
                    gpuMax = null,
                    resetToStock = true,
                    cpuIds = listOf("policy0", "policy4"),
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        val externalRewrite = fixture.fs.operations.indexOf("external:max0=800")
        assertTrue(externalRewrite >= 0)
        assertFalse(fixture.fs.operations.drop(externalRewrite + 1).any { it == "write:max0=1000" })
    }

    @Test
    fun `confirmed stock GPU baseline does not claim a later external fallback`() {
        val fixture = hiddenStockGpuFixture()
        fixture.fs.rewriteAfterReads(
            path = "gmax",
            value = "900",
            // GPU verification performs additional accepted-maximum reads.
            reads = 7,
            failNextModeAfterRewrite = true,
        )

        val stopped = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = ApplyRequest(
                    cpuMax = listOf(800),
                    gpuMax = 1000,
                    resetToStock = true,
                    cpuIds = listOf("policy0"),
                    gpuId = "gpu0",
                    gpuMaxPath = "gmax",
                    stabilizedStockCeiling = 1000,
                    maximumsOnly = true,
                ),
            ),
        )

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals(900L, fixture.fs.values["gmax"]?.toLong())
        val externalRewrite = fixture.fs.operations.indexOf("external:gmax=900")
        assertTrue(externalRewrite >= 0)
        assertFalse(fixture.fs.operations.drop(externalRewrite + 1).any { it == "write:gmax=1000" })
    }

    @Test
    fun `ceiling change during startup is preserved and session is rejected`() {
        val fixture = fixture()
        fixture.fs.rewriteAfterReads("max", "700")
        val operationsBeforeStart = fixture.fs.operations.size

        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))

        assertEquals(HostAutoSessionStatus.UNSUPPORTED, started.status)
        assertTrue(started.message.orEmpty().contains("changed while Auto Tune was starting"))
        assertEquals("700", fixture.fs.values["max"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeStart).any { it.startsWith("write:max=") })
        assertEquals(HostAutoSessionStatus.STOPPED, fixture.controller.current().status)
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
    fun `one-domain step does not claim an unchanged domain mode`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val oneDomainStep = ApplyRequest(
            cpuMax = listOf(600, 1200),
            gpuMax = 900,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
            gpuId = "gpu0",
            gpuMaxPath = "gmax",
        )

        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, oneDomainStep).status,
        )
        fixture.fs.modes["max1"] = 292
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals(420, fixture.fs.modes["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals(292, fixture.fs.modes["max1"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStop).any {
                it.startsWith("write:max1=") || it.startsWith("chmod:max1=")
            },
        )
    }

    @Test
    fun `fixed efficiency step leaves unchanged envelope ceilings protected and untouched`() {
        val fixture = multiDomainFixture()
        val baseline = multiRequest().copy(maximumsOnly = true)
        val started = fixture.controller.start(
            AutoSessionRequest("com.game", 60, 5_000, baseline = baseline),
        )
        val session = requireNotNull(started.sessionId)
        assertEquals(HostAutoSessionStatus.ACTIVE, started.status)
        assertEquals(292, fixture.fs.modes["max0"])
        assertEquals(292, fixture.fs.modes["max1"])
        assertEquals(292, fixture.fs.modes["gmax"])
        val operationsBeforeStep = fixture.fs.operations.size

        val applied = fixture.controller.applyStep(
            session,
            started.hostEpoch,
            baseline.copy(cpuMax = listOf(400, 900), maximumsOnly = false),
        )

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("400", fixture.fs.values["max0"])
        assertEquals("900", fixture.fs.values["max1"])
        assertEquals("600", fixture.fs.values["gmax"])
        assertEquals(292, fixture.fs.modes["max0"])
        assertEquals(292, fixture.fs.modes["max1"])
        assertEquals(292, fixture.fs.modes["gmax"])
        val stepOperations = fixture.fs.operations.drop(operationsBeforeStep)
        assertTrue(stepOperations.any { it == "write:max0=400" })
        assertFalse(stepOperations.any { it.contains("max1") || it.contains("gmax") })
    }

    @Test
    fun `trim on another domain preserves protected physical stock session node`() {
        val fixture = multiDomainFixture()
        fixture.fs.modes["max0"] = 288 // 0440, protected although policy0 is at physical Stock.
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val operationsBeforeStep = fixture.fs.operations.size
        val request = ApplyRequest(
            cpuMax = listOf(800, 900),
            gpuMax = 900,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
            gpuId = "gpu0",
            gpuMaxPath = "gmax",
        )

        val applied = fixture.controller.applyStep(session, started.hostEpoch, request)

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals(288, fixture.fs.modes["max0"])
        assertEquals("900", fixture.fs.values["max1"])
        val stepOperations = fixture.fs.operations.drop(operationsBeforeStep)
        assertTrue(stepOperations.any { it == "write:max1=900" })
        assertFalse(stepOperations.any { it.contains("max0") })
    }

    @Test
    fun `indeterminate one-domain step does not claim an unchanged domain mode`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val oneDomainStep = ApplyRequest(
            cpuMax = listOf(600, 1200),
            gpuMax = 900,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
            gpuId = "gpu0",
            gpuMaxPath = "gmax",
        )
        fixture.fs.failNextMutationIndeterminately(afterOperations = 1)
        fixture.fs.rewriteOnNextIndeterminateMutation("max1", "1200", mode = 292)

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, oneDomainStep)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals(292, fixture.fs.modes["max1"])
        val externalRewrite = fixture.fs.operations.indexOf("external:max1=1200 mode=292")
        assertTrue(externalRewrite >= 0)
        assertFalse(
            fixture.fs.operations.drop(externalRewrite + 1).any {
                it.startsWith("write:max1=") || it.startsWith("chmod:max1=")
            },
        )
    }

    @Test
    fun `heartbeat tolerates an exact Stock reset without writing and reports the live state`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        val mutationsAfterFirst = fixture.fs.batchMutations
        fixture.fs.values["max"] = "800"
        val operationsBeforeHeartbeat = fixture.fs.operations.size

        val held = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals(listOf(800L), requireNotNull(held.state).cpuMax)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(mutationsAfterFirst, fixture.fs.batchMutations)
        assertEquals(operationsBeforeHeartbeat, fixture.fs.operations.size)
    }

    @Test
    fun `telemetry polling tolerates an exact Stock reset without writing`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.values["max"] = "800"
        val mutationsBeforePoll = fixture.fs.batchMutations
        val operationsBeforePoll = fixture.fs.operations.size

        val held = fixture.controller.readTelemetry(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(mutationsBeforePoll, fixture.fs.batchMutations)
        assertEquals(operationsBeforePoll, fixture.fs.operations.size)
    }

    @Test
    fun `both physical Stock aliases are tolerated while an intermediate upward value is external`() {
        listOf(1_000L, 800L).forEach { stockAlias ->
            val fixture = hiddenStockFixture()
            val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
            val session = requireNotNull(started.sessionId)
            val trimmed = ApplyRequest(
                cpuMax = listOf(400, 900),
                gpuMax = null,
                resetToStock = false,
                cpuIds = listOf("policy0", "policy4"),
            )
            assertEquals(
                HostAutoSessionStatus.ACTIVE,
                fixture.controller.applyStep(session, started.hostEpoch, trimmed).status,
            )
            fixture.fs.values["max0"] = stockAlias.toString()
            val mutationsBeforePoll = fixture.fs.batchMutations

            val held = fixture.controller.heartbeat(session, started.hostEpoch)

            assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
            assertEquals(stockAlias, requireNotNull(held.state).cpuMax[0])
            assertEquals(mutationsBeforePoll, fixture.fs.batchMutations)
        }

        val fixture = hiddenStockFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(
            session,
            started.hostEpoch,
            ApplyRequest(
                cpuMax = listOf(400, 900),
                gpuMax = null,
                resetToStock = false,
                cpuIds = listOf("policy0", "policy4"),
            ),
        )
        fixture.fs.values["max0"] = "700"

        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("700", fixture.fs.values["max0"])
    }

    @Test
    fun `next automatic step reapplies its complete map after a tolerated Stock reset without touching minimums`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "800"
        val minimumValues = listOf("min0", "min1", "gmin").associateWith { fixture.fs.values[it] }
        val minimumModes = listOf("min0", "min1", "gmin").associateWith { fixture.fs.modes[it] }
        val operationsBeforeStep = fixture.fs.operations.size
        val mutationsBeforeStep = fixture.fs.batchMutations
        val next = ApplyRequest(
            cpuMax = listOf(400, 1200),
            gpuMax = 900,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
            gpuId = "gpu0",
            gpuMaxPath = "gmax",
        )

        val reapplied = fixture.controller.applyStep(session, started.hostEpoch, next)

        assertEquals(HostAutoSessionStatus.ACTIVE, reapplied.status)
        assertEquals(listOf(400L, 1200L), requireNotNull(reapplied.state).cpuMax)
        assertEquals(900L, reapplied.state?.gpuMax)
        assertEquals("400", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(mutationsBeforeStep + 1, fixture.fs.batchMutations)
        val stepOperations = fixture.fs.operations.drop(operationsBeforeStep)
        assertTrue(stepOperations.contains("write:max0=400"))
        assertTrue(stepOperations.contains("write:max1=1200"))
        assertTrue(stepOperations.contains("write:gmax=900"))
        assertFalse(stepOperations.any { it.contains("min0") || it.contains("min1") || it.contains("gmin") })
        assertEquals(minimumValues, listOf("min0", "min1", "gmin").associateWith { fixture.fs.values[it] })
        assertEquals(minimumModes, listOf("min0", "min1", "gmin").associateWith { fixture.fs.modes[it] })
    }

    @Test
    fun `a Stock reset in one domain cannot mask a lower external cap in another`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "800"
        fixture.fs.values["max1"] = "700"
        val operationsBeforePoll = fixture.fs.operations.size

        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("policy4: 900->700"))
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("700", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        val stopOperations = fixture.fs.operations.drop(operationsBeforePoll)
        assertFalse(stopOperations.any { it.startsWith("write:max0=") || it.startsWith("write:max1=") })
        assertTrue(stopOperations.contains("write:gmax=900"))
        assertFalse(stopOperations.any { it.contains("min") })
    }

    @Test
    fun `a Stock reset repeated during the next apply stays active after one bounded max-only write`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.values["max"] = "800"
        fixture.fs.rewriteAfterNextBatchRead("max", "800")
        val operationsBeforeStep = fixture.fs.operations.size
        val mutationsBeforeStep = fixture.fs.batchMutations

        val applied = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals(listOf(600L), requireNotNull(applied.state).cpuMax)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(292, fixture.fs.modes["max"])
        assertEquals(mutationsBeforeStep + 1, fixture.fs.batchMutations)
        val stepOperations = fixture.fs.operations.drop(operationsBeforeStep)
        assertEquals(listOf("write:max=600"), stepOperations.filter { it.startsWith("write:max=") })
        assertFalse(stepOperations.any { it.contains("min") })

        val held = fixture.controller.heartbeat(session, started.hostEpoch)
        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals(listOf(800L), requireNotNull(held.state).cpuMax)
        assertEquals(mutationsBeforeStep + 1, fixture.fs.batchMutations)
    }

    @Test
    fun `a Stock reset during authoritative post-batch verification stays active after one max-only write`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.rewriteBeforeNextBatchRead("max", "800")
        val operationsBeforeStep = fixture.fs.operations.size

        val applied = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals(listOf(800L), requireNotNull(applied.state).cpuMax)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(292, fixture.fs.modes["max"])
        val stepOperations = fixture.fs.operations.drop(operationsBeforeStep)
        assertEquals(listOf("write:max=600"), stepOperations.filter { it.startsWith("write:max=") })
        assertFalse(stepOperations.any { it.contains("min") })
    }

    @Test
    fun `a no-write Stock takeover during apply is relinquished instead of stopped`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400)).status,
        )
        // The controller sees 400. Before HostApplyEngine snapshots the node, the OEM selects
        // physical Stock 800, so the engine correctly emits no value write for this request.
        fixture.fs.rewriteAfterReads("max", "800", reads = 1)
        val operationsBeforeStep = fixture.fs.operations.size

        val held = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800))

        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals(listOf(800L), requireNotNull(held.state).cpuMax)
        assertEquals("800", fixture.fs.values["max"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStep).any { it.startsWith("write:max=") },
        )
        val operationsBeforeStop = fixture.fs.operations.size
        val stopped = fixture.controller.stop(session, started.hostEpoch)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStop).any { it.startsWith("write:max=") },
        )
    }

    @Test
    fun `a non-Stock post-write mismatch stops and remains externally owned`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600)).status,
        )
        fixture.fs.rewriteBeforeNextBatchRead("max", "600")
        val operationsBeforeStep = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("400->600"))
        assertEquals("600", fixture.fs.values["max"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStep).any { it == "write:max=800" },
        )
    }

    @Test
    fun `a request-matching OEM Stock alias remains unowned when another domain changes`() {
        val fixture = hiddenStockFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val first = ApplyRequest(
            cpuMax = listOf(400, 900),
            gpuMax = null,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
        )
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, first).status,
        )
        fixture.fs.values["max0"] = "800"
        val operationsBeforeSecondStep = fixture.fs.operations.size
        val second = first.copy(cpuMax = listOf(800, 1200))

        val applied = fixture.controller.applyStep(session, started.hostEpoch, second)

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals(listOf(800L, 1200L), requireNotNull(applied.state).cpuMax)
        val secondStepOperations = fixture.fs.operations.drop(operationsBeforeSecondStep)
        assertFalse(secondStepOperations.any { it.startsWith("write:max0=") })
        assertTrue(secondStepOperations.contains("write:max1=1200"))
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals(420, fixture.fs.modes["max0"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStop).any { it.startsWith("write:max0=") },
        )
    }

    @Test
    fun `an exact Stock reset tolerates OEM minimum changes but not maximum mode drift`() {
        run {
            val fixture = fixture()
            val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
            val session = requireNotNull(started.sessionId)
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
            fixture.fs.values["max"] = "800"
            fixture.fs.values["min"] = "400"
            val operationsBeforePoll = fixture.fs.operations.size

            val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

            assertEquals(HostAutoSessionStatus.ACTIVE, stopped.status)
            assertEquals("800", fixture.fs.values["max"])
            assertEquals("400", fixture.fs.values["min"])
            assertFalse(fixture.fs.operations.drop(operationsBeforePoll).any { it.startsWith("write:") })
        }

        run {
            val fixture = fixture()
            val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
            val session = requireNotNull(started.sessionId)
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
            fixture.fs.values["max"] = "800"
            fixture.fs.modes["max"] = 384
            val operationsBeforePoll = fixture.fs.operations.size

            val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

            assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
            assertTrue(stopped.message.orEmpty().contains("mode"))
            assertEquals("800", fixture.fs.values["max"])
            assertEquals(384, fixture.fs.modes["max"])
            assertEquals(operationsBeforePoll, fixture.fs.operations.size)
        }
    }

    @Test
    fun `tolerated CPU and GPU Stock resets cannot hide a lower drift in another domain`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "800"
        fixture.fs.values["gmax"] = "900"

        val held = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals(listOf(800L, 900L), requireNotNull(held.state).cpuMax)
        assertEquals(900L, held.state?.gpuMax)

        fixture.fs.values["max1"] = "700"
        val operationsBeforeStop = fixture.fs.operations.size
        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("700", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStop).any {
                it.startsWith("write:max0=") || it.startsWith("write:max1=") || it.startsWith("write:gmax=")
            },
        )
    }

    @Test
    fun `an unreadable maximum is never treated as a tolerated reset`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextReads("max", count = 1)

        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("external maximum changed"))
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `telemetry polling detects maximum drift while controller is holding`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        val mutationsAfterApply = fixture.fs.batchMutations
        fixture.fs.values["max"] = "400"

        val stopped = fixture.controller.readTelemetry(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("400", fixture.fs.values["max"])
        assertEquals(420, fixture.fs.modes["max"])
        assertEquals(mutationsAfterApply, fixture.fs.batchMutations)
    }

    @Test
    fun `drift in one CPU preserves it while restoring every other owned ceiling`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "700"
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.readTelemetry(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals(420, fixture.fs.modes["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        val restoreOperations = fixture.fs.operations.drop(operationsBeforeStop)
        assertFalse(restoreOperations.any { it.startsWith("write:max0=") })
        assertTrue(restoreOperations.contains("write:max1=1200"))
        assertTrue(restoreOperations.contains("write:gmax=900"))
    }

    @Test
    fun `explicit stop preserves a tolerated physical Stock reset when no later apply reclaimed it`() {
        val fixture = fixture()
        val baseline = request(cpu = 600).copy(maximumsOnly = true)
        val started = fixture.controller.start(
            AutoSessionRequest("com.game", 60, 5_000, baseline = baseline),
        )
        val session = requireNotNull(started.sessionId)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400)).status,
        )
        fixture.fs.values["max"] = "800"
        val operationsBeforeHeartbeat = fixture.fs.operations.size

        val held = fixture.controller.heartbeat(session, started.hostEpoch)
        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.ACTIVE, held.status)
        assertEquals(listOf(800L), requireNotNull(held.state).cpuMax)
        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        // The session checkpoint is 600. Since the OEM reset remained live, stop must not
        // reclaim it merely because 800 is a recognized physical Stock alias.
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(292, fixture.fs.modes["max"])
        assertEquals("200", fixture.fs.values["min"])
        val stopOperations = fixture.fs.operations.drop(operationsBeforeHeartbeat)
        assertFalse(stopOperations.any { it.startsWith("write:max=") })
        assertFalse(stopOperations.any { it.contains("min") })
    }

    @Test
    fun `a relinquished Stock reset cannot be reclaimed by numeric ABA`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600)).status,
        )
        fixture.fs.values["max"] = "800"
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.heartbeat(session, started.hostEpoch).status,
        )

        // The OEM later happens to select Auto Tune's former numeric value. Since no later
        // ClusterTune write reclaimed this path, the value remains externally owned.
        fixture.fs.values["max"] = "600"
        val operationsBeforeStop = fixture.fs.operations.size
        val stopped = fixture.controller.heartbeat(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals(420, fixture.fs.modes["max"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeStop).any { it.startsWith("write:max=") },
        )
    }

    @Test
    fun `an apply failure cannot reclaim a previously relinquished Stock reset`() {
        val fixture = hiddenStockFixture()
        val baseline = ApplyRequest(
            cpuMax = listOf(800, 1200),
            gpuMax = null,
            resetToStock = false,
            cpuIds = listOf("policy0", "policy4"),
            maximumsOnly = true,
        )
        val started = fixture.controller.start(
            AutoSessionRequest("com.game", 60, 5_000, baseline = baseline),
        )
        val session = requireNotNull(started.sessionId)
        val trimmed = baseline.copy(cpuMax = listOf(400, 1200), maximumsOnly = false)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, trimmed).status,
        )
        fixture.fs.values["max0"] = "1000"
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.heartbeat(session, started.hostEpoch).status,
        )
        fixture.fs.failNextChmods("max0", 420, count = 1)
        val operationsBeforeFailure = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, baseline.copy(maximumsOnly = false))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("1000", fixture.fs.values["max0"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeFailure).any { it.startsWith("write:max0=") },
        )
    }

    @Test
    fun `explicit stop preserves external GPU ceiling and restores owned CPUs`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["gmax"] = "750"
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("750", fixture.fs.values["gmax"])
        val restoreOperations = fixture.fs.operations.drop(operationsBeforeStop)
        assertTrue(restoreOperations.contains("write:max0=800"))
        assertTrue(restoreOperations.contains("write:max1=1200"))
        assertFalse(restoreOperations.any { it.startsWith("write:gmax=") })
    }

    @Test
    fun `profile preemption preserves external CPU ceiling and restores owned domains`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(requireNotNull(started.sessionId), started.hostEpoch, multiRequest())
        fixture.fs.values["max1"] = "1000"

        val stopped = requireNotNull(fixture.controller.stopCurrent("fixed profile preemption"))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1000", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `session replacement preserves drift before capturing the new baseline`() {
        val fixture = multiDomainFixture()
        val first = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(requireNotNull(first.sessionId), first.hostEpoch, multiRequest())
        fixture.fs.values["gmax"] = "750"
        val operationsBeforeReplacement = fixture.fs.operations.size

        val second = fixture.controller.start(AutoSessionRequest("com.other", 30, 5_000))

        assertEquals(HostAutoSessionStatus.ACTIVE, second.status)
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("750", fixture.fs.values["gmax"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeReplacement).any { it.startsWith("write:gmax=") },
        )
    }

    @Test
    fun `expiry preserves external CPU ceiling and restores other owned domains`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max1"] = "1000"
        fixture.clock.now += 5_000_000_000L

        val expired = requireNotNull(fixture.controller.expireIfNeeded())

        assertEquals(HostAutoSessionStatus.EXPIRED, expired.status)
        assertTrue(expired.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1000", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `pending restoration never reclaims a domain excluded by its first ownership check`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "700"
        fixture.fs.failNextWrites("max1", count = 1)

        val failed = fixture.controller.stop(session, started.hostEpoch)
        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertEquals("700", fixture.fs.values["max0"])

        // Even if the external actor later happens to select Auto Tune's old value, the retry
        // keeps the original ownership plan and must not restore this path to the checkpoint.
        fixture.fs.values["max0"] = "600"
        val operationsBeforeRetry = fixture.fs.operations.size
        val restored = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("600", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeRetry).any { it.startsWith("write:max0=") })
    }

    @Test
    fun `pending retry relinquishes a ceiling restored before another domain failed`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        // Restore iterates GPU, max1, then max0. Leave max0 pending while max1 is restored.
        fixture.fs.failNextWrites("max0", count = 1)
        val failed = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals("1200", fixture.fs.values["max1"])

        // An external actor can legitimately choose the old Auto value after the first retry.
        // That value must not cause the already-restored path to be reclaimed on the next retry.
        fixture.fs.values["max1"] = "900"
        val operationsBeforeRetry = fixture.fs.operations.size
        val restored = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("900", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeRetry).any { it == "write:max1=1200" })
    }

    @Test
    fun `post-apply OEM rewrite is not adopted and unaffected domains are restored`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.rewriteBeforeNextBatchRead("max0", "700")

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("external maximum changed after apply"))
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `post-apply Stock reset stays external while stop restores other owned domains and mode`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.rewriteBeforeNextBatchRead("max0", "800")

        val applied = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals(listOf(800L, 900L), requireNotNull(applied.state).cpuMax)
        assertEquals(600L, applied.state?.gpuMax)
        assertEquals(292, fixture.fs.modes["max0"])
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(420, fixture.fs.modes["max0"])
        val stopOperations = fixture.fs.operations.drop(operationsBeforeStop)
        assertFalse(stopOperations.any { it.startsWith("write:max0=") })
        assertTrue(stopOperations.contains("write:max1=1200"))
        assertTrue(stopOperations.contains("write:gmax=900"))
        assertTrue(stopOperations.contains("chmod:max0=420"))
    }

    @Test
    fun `mode-only drift stops before another step and preserves the external mode`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600)).status,
        )
        fixture.fs.modes["max"] = 384 // An external 0600 mode, not an Auto Tune protection mode.
        val operationsBeforeSecondStep = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("mode"))
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(384, fixture.fs.modes["max"])
        assertFalse(
            fixture.fs.operations.drop(operationsBeforeSecondStep).any { it == "write:max=400" },
        )
    }

    @Test
    fun `stop restores group-writable mode after trim and envelope recovery`() {
        val fixture = fixture()
        fixture.fs.modes["max"] = 436 // 0664
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600)).status,
        )
        assertEquals(292, fixture.fs.modes["max"]) // 0444
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 800)).status,
        )
        assertEquals(420, fixture.fs.modes["max"]) // 0644

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(436, fixture.fs.modes["max"])
    }

    @Test
    fun `OEM rewrite during max-only verification is preserved by rollback`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.rewriteBeforeNextBatchRead("max0", "700")

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertEquals(listOf("write:max0=600"), fixture.fs.operations.filter { it.startsWith("write:max0=") })
    }

    @Test
    fun `failed preflight does not claim an external target-valued ceiling`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        // The controller observes the old ceiling, then an external actor selects the same
        // value as this request before HostApplyEngine fails without mutating anything.
        fixture.fs.rewriteAfterReads("max", "600")
        fixture.fs.retainMutationFailure(HostDispatchFailure(true, "stale fake dispatch failure"))
        fixture.fs.modes.remove("max")
        val operationsBeforeApply = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("600", fixture.fs.values["max"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeApply).any { it.startsWith("write:max=") })
    }

    @Test
    fun `indeterminate partial dispatch restores every surviving automatic target`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.failNextMutationIndeterminately(afterOperations = 3)

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertTrue(fixture.fs.operations.contains("write:max0=600"))
        assertTrue(fixture.fs.operations.contains("write:max0=800"))
    }

    @Test
    fun `partial apply with incomplete rollback remains latched until owned cap restores`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.fs.failNextWrites("max1", count = 1)
        fixture.fs.failNextWriteValue("max0", "800", count = 2)

        val failed = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals("600", fixture.fs.values["max0"])

        val restored = fixture.controller.stop(session, started.hostEpoch)
        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `unreadable owned ceiling keeps restoration pending until ownership can be verified`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextReads("max", count = 2)

        val failed = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertTrue(failed.message.orEmpty().contains("ownership unreadable"))
        assertEquals("600", fixture.fs.values["max"])

        val restored = fixture.controller.stop(session, started.hostEpoch)
        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `mode ownership is relinquished after mode restore while ceiling ownership remains unreadable`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextReads("max", count = 2)

        val failed = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals("600", fixture.fs.values["max"])
        assertEquals(420, fixture.fs.modes["max"])

        // This mode is indistinguishable from Auto Tune's old protected mode by value alone.
        // Since the first attempt already restored and relinquished the mode, the retry must
        // preserve this later external choice while it restores the still-owned ceiling value.
        fixture.fs.modes["max"] = 292
        val restored = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(292, fixture.fs.modes["max"])
    }

    @Test
    fun `failed final chmod keeps widened external mode latched until retry restores it`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.modes["max"] = 256 // External 0400; value restore must temporarily use 0600.
        fixture.fs.failNextChmods("max", mode = 256, count = 1)

        val failed = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(384, fixture.fs.modes["max"])

        val restored = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(256, fixture.fs.modes["max"])
    }

    @Test
    fun `unreadable ownership followed by an external ceiling never reclaims the path`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextReads("max", count = 2)
        val operationsBeforeStop = fixture.fs.operations.size

        val failed = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(fixture.fs.operations.drop(operationsBeforeStop).any { it.startsWith("write:max=") })

        fixture.fs.values["max"] = "500"
        val operationsBeforeRetry = fixture.fs.operations.size
        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("500", fixture.fs.values["max"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeRetry).any { it.startsWith("write:max=") })
    }

    @Test
    fun `external maximum and permission mode both remain authoritative on stop`() {
        val fixture = multiDomainFixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        fixture.controller.applyStep(session, started.hostEpoch, multiRequest())
        fixture.fs.values["max0"] = "700"
        fixture.fs.modes["max0"] = 384 // 0600, not an Auto Tune-owned mode for original 0644.

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals(384, fixture.fs.modes["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
    }

    @Test
    fun `watchdog retries a failed expiry restoration`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        fixture.controller.applyStep(requireNotNull(started.sessionId), started.hostEpoch, request(cpu = 600))
        fixture.fs.failNextWrites("max", count = 1)
        fixture.clock.now += 5_000_000_000L

        val failed = requireNotNull(fixture.controller.expireIfNeeded())
        val restored = requireNotNull(fixture.controller.expireIfNeeded())

        assertEquals(HostAutoSessionStatus.RESTORE_FAILED, failed.status)
        assertFalse(failed.restorationComplete)
        assertEquals(HostAutoSessionStatus.EXPIRED, restored.status)
        assertTrue(restored.restorationComplete)
        assertEquals("800", fixture.fs.values["max"])
    }

    @Test
    fun `unknown live GPU minimum keeps only that domain inert at its base ceiling`() {
        val fs = FakeFs(
            values = mutableMapOf("min" to "200", "max" to "800", "gmin" to "0", "gmax" to "900"),
            modes = mutableMapOf("min" to 416, "max" to 420, "gmin" to 416, "gmax" to 420),
        )
        val base = capabilities(withGpu = true)
        val hiddenGpu = requireNotNull(base.gpu).copy(
            supportedFrequencies = emptyList(),
            selectableMax = 600,
            stockMax = 900,
            observedMax = 900,
        )
        val capabilities = base.copy(gpu = hiddenGpu)
        val controller = HostAutoSessionController(
            capabilities,
            fs,
            HostApplyEngine(fs),
            FakeTelemetry(capabilities.cpus.size, hasGpu = true),
            hostEpoch = 42L,
            clock = FakeClock(),
        )
        val started = controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)
        val operationsBefore = fs.operations.toList()
        val batchesBefore = fs.batchMutations

        assertEquals(0L, started.state?.gpuMin)
        assertFails { controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 600)) }
        assertEquals(operationsBefore, fs.operations)
        assertEquals(batchesBefore, fs.batchMutations)

        val trimmed = controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 900))
        assertEquals(trimmed.toString(), HostAutoSessionStatus.ACTIVE, trimmed.status)

        assertEquals("600", fs.values["max"])
        assertEquals("900", fs.values["gmax"])
        assertFails {
            controller.applyStep(
                session,
                started.hostEpoch,
                request(cpu = 800, gpu = 900).copy(stabilizedStockCeiling = 900),
            )
        }
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

    private fun fixture(
        withGpu: Boolean = false,
        cpuMin: Long = 200,
        gpuMin: Long = 300,
        telemetryBeginFailure: Throwable? = null,
    ): Fixture {
        val values = mutableMapOf("min" to cpuMin.toString(), "max" to "800")
        val modes = mutableMapOf("min" to 416, "max" to 420)
        if (withGpu) {
            values.putAll(mapOf("gmin" to gpuMin.toString(), "gmax" to "900"))
            modes.putAll(mapOf("gmin" to 416, "gmax" to 420))
        }
        val fs = FakeFs(values, modes)
        val capabilities = capabilities(withGpu)
        val clock = FakeClock()
        val telemetry = FakeTelemetry(capabilities.cpus.size, withGpu, telemetryBeginFailure)
        val controller = HostAutoSessionController(
            capabilities,
            fs,
            HostApplyEngine(fs),
            telemetry,
            hostEpoch = 42L,
            clock = clock,
        )
        return Fixture(fs, clock, telemetry, controller)
    }

    private fun multiDomainFixture(): Fixture {
        val fs = FakeFs(
            values = mutableMapOf(
                "min0" to "200",
                "max0" to "800",
                "min1" to "500",
                "max1" to "1200",
                "gmin" to "300",
                "gmax" to "900",
            ),
            modes = mutableMapOf(
                "min0" to 416,
                "max0" to 420,
                "min1" to 416,
                "max1" to 420,
                "gmin" to 416,
                "gmax" to 420,
            ),
        )
        val capabilities = multiDomainCapabilities()
        val clock = FakeClock()
        val telemetry = FakeTelemetry(capabilities.cpus.size, hasGpu = true)
        return Fixture(
            fs,
            clock,
            telemetry,
            HostAutoSessionController(
                capabilities,
                fs,
                HostApplyEngine(fs),
                telemetry,
                hostEpoch = 42L,
                clock = clock,
            ),
        )
    }

    private fun hiddenStockFixture(): Fixture {
        val fs = FakeFs(
            values = mutableMapOf(
                "min0" to "200",
                "max0" to "1000",
                "min1" to "500",
                "max1" to "1200",
            ),
            modes = mutableMapOf(
                "min0" to 416,
                "max0" to 420,
                "min1" to 416,
                "max1" to 420,
            ),
        )
        val capabilities = HostCapabilities(
            cpus = listOf(
                CpuDomain(
                    "policy0", "min0", "max0", null,
                    listOf(200), listOf(400, 800), 1000, 1000, 200,
                    selectableMax = 800, currentMax = 1000,
                ),
                CpuDomain(
                    "policy4", "min1", "max1", null,
                    listOf(500), listOf(500, 900, 1200), 1200, 1200, 500,
                    selectableMax = 1200, currentMax = 1200,
                ),
            ),
            gpu = null,
        )
        val clock = FakeClock()
        val telemetry = FakeTelemetry(cpuCount = 2, hasGpu = false)
        return Fixture(
            fs,
            clock,
            telemetry,
            HostAutoSessionController(
                capabilities,
                fs,
                HostApplyEngine(fs),
                telemetry,
                hostEpoch = 42L,
                clock = clock,
            ),
        )
    }

    private fun hiddenStockGpuFixture(): Fixture {
        val fs = FakeFs(
            values = mutableMapOf(
                "min" to "200",
                "max" to "800",
                "gmin" to "300",
                "gmax" to "1000",
            ),
            modes = mutableMapOf(
                "min" to 416,
                "max" to 420,
                "gmin" to 416,
                "gmax" to 420,
            ),
        )
        val capabilities = HostCapabilities(
            cpus = listOf(
                CpuDomain(
                    "policy0", "min", "max", null,
                    listOf(200), listOf(400, 800), 800, 800, 200,
                    selectableMax = 800, currentMax = 800,
                ),
            ),
            gpu = GpuDomain(
                "gpu0", "gmin", "gmax", null,
                listOf(300, 600, 900), 1000, 1000, 300,
                selectableMax = 900, currentMax = 1000,
            ),
        )
        val clock = FakeClock()
        val telemetry = FakeTelemetry(cpuCount = 1, hasGpu = true)
        return Fixture(
            fs,
            clock,
            telemetry,
            HostAutoSessionController(
                capabilities,
                fs,
                HostApplyEngine(fs),
                telemetry,
                hostEpoch = 42L,
                clock = clock,
            ),
        )
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

    private fun multiDomainCapabilities(): HostCapabilities = HostCapabilities(
        cpus = listOf(
            CpuDomain(
                id = "policy0",
                minPath = "min0",
                maxPath = "max0",
                curPath = null,
                minimumCandidates = listOf(200),
                supportedFrequencies = listOf(400, 600, 800),
                stockMax = 800,
                observedMax = 800,
                observedMin = 200,
                selectableMax = 800,
                currentMax = 800,
            ),
            CpuDomain(
                id = "policy4",
                minPath = "min1",
                maxPath = "max1",
                curPath = null,
                minimumCandidates = listOf(500),
                supportedFrequencies = listOf(500, 900, 1200),
                stockMax = 1200,
                observedMax = 1200,
                observedMin = 500,
                selectableMax = 1200,
                currentMax = 1200,
            ),
        ),
        gpu = GpuDomain(
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
        ),
    )

    private fun request(cpu: Long, gpu: Long? = null, reset: Boolean = false) = ApplyRequest(
        cpuMax = listOf(cpu),
        gpuMax = gpu,
        resetToStock = reset,
        cpuIds = listOf("policy0"),
        gpuId = gpu?.let { "gpu0" },
        gpuMaxPath = gpu?.let { "gmax" },
    )

    private fun multiRequest() = ApplyRequest(
        cpuMax = listOf(600, 900),
        gpuMax = 600,
        resetToStock = false,
        cpuIds = listOf("policy0", "policy4"),
        gpuId = "gpu0",
        gpuMaxPath = "gmax",
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
        val telemetry: FakeTelemetry,
        val controller: HostAutoSessionController,
    )

    private class FakeClock(var now: Long = 1_000L) : HostMonotonicClock {
        override fun nanoTime(): Long = now
    }

    private class FakeTelemetry(
        private val cpuCount: Int,
        private val hasGpu: Boolean,
        private val beginFailure: Throwable? = null,
    ) : HostTelemetrySource {
        var beginCount = 0
        var endCount = 0
        var onBegin: (() -> Unit)? = null
        val begunTargets = mutableListOf<Int>()
        override fun capabilities() = HostAutoCapabilities(true, true, false, hasGpu, false, false, "fake")
        override fun begin(packageName: String, targetFps: Int): Result<Unit> {
            beginCount++
            begunTargets += targetFps
            onBegin?.invoke()
            return beginFailure?.let(Result.Companion::failure) ?: Result.success(Unit)
        }
        override fun end() {
            endCount++
        }
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
        private val failedWritesRemaining = mutableMapOf<String, Int>()
        private val failedWriteValuesRemaining = mutableMapOf<Pair<String, String>, Int>()
        private val failedReadsRemaining = mutableMapOf<String, Int>()
        private val failedChmodsRemaining = mutableMapOf<Pair<String, Int>, Int>()
        private var nextIndeterminateMutationPrefix: Int? = null
        private data class IndeterminateRewrite(val path: String, val value: String, val mode: Int?)
        private var nextIndeterminateRewrite: IndeterminateRewrite? = null
        private var mutationFailure: Throwable? = null
        private data class RewriteAfterBatch(
            val path: String,
            val value: String,
            var readsRemaining: Int,
            val failNextModeAfterRewrite: Boolean = false,
        )
        private var armedRewrite: RewriteAfterBatch? = null
        private var activeRewrite: RewriteAfterBatch? = null
        private var armedRewriteBefore: RewriteAfterBatch? = null
        private var activeRewriteBefore: RewriteAfterBatch? = null
        private val failedModeReadsRemaining = mutableMapOf<String, Int>()
        var batchMutations = 0
        override fun read(path: String): String? {
            val readFailures = failedReadsRemaining[path] ?: 0
            if (readFailures > 0) {
                failedReadsRemaining[path] = readFailures - 1
                return null
            }
            activeRewriteBefore?.takeIf { it.path == path }?.let { rewrite ->
                rewrite.readsRemaining--
                if (rewrite.readsRemaining <= 0) {
                    values[path] = rewrite.value
                    activeRewriteBefore = null
                }
            }
            val result = values[path]
            activeRewrite?.takeIf { it.path == path }?.let { rewrite ->
                rewrite.readsRemaining--
                if (rewrite.readsRemaining <= 0) {
                    values[path] = rewrite.value
                    operations += "external:$path=${rewrite.value}"
                    if (rewrite.failNextModeAfterRewrite) failedModeReadsRemaining[path] = 1
                    activeRewrite = null
                }
            }
            return result
        }
        override fun write(path: String, value: String): Boolean {
            operations += "write:$path=$value"
            val failures = failedWritesRemaining[path] ?: 0
            if (failures > 0) {
                failedWritesRemaining[path] = failures - 1
                return false
            }
            val valueKey = path to value
            val valueFailures = failedWriteValuesRemaining[valueKey] ?: 0
            if (valueFailures > 0) {
                failedWriteValuesRemaining[valueKey] = valueFailures - 1
                return false
            }
            values[path] = value
            return true
        }
        override fun mode(path: String): Int? {
            val failures = failedModeReadsRemaining[path] ?: 0
            if (failures > 0) {
                failedModeReadsRemaining[path] = failures - 1
                return null
            }
            return modes[path]
        }
        override fun chmod(path: String, mode: Int): Boolean {
            operations += "chmod:$path=$mode"
            val key = path to mode
            val failures = failedChmodsRemaining[key] ?: 0
            if (failures > 0) {
                failedChmodsRemaining[key] = failures - 1
                return false
            }
            modes[path] = mode
            return true
        }
        override fun exists(path: String): Boolean = path in values
        override fun lastMutationFailure(): Throwable? = mutationFailure
        override fun mutate(operations: List<HostMutation>): Boolean {
            batchMutations++
            mutationFailure = null
            nextIndeterminateMutationPrefix?.let { prefixSize ->
                nextIndeterminateMutationPrefix = null
                super<HostFilesystem>.mutate(operations.take(prefixSize))
                nextIndeterminateRewrite?.let { rewrite ->
                    values[rewrite.path] = rewrite.value
                    rewrite.mode?.let { modes[rewrite.path] = it }
                    this.operations += "external:${rewrite.path}=${rewrite.value}" +
                        rewrite.mode?.let { " mode=$it" }.orEmpty()
                }
                nextIndeterminateRewrite = null
                mutationFailure = HostDispatchFailure(true, "fake indeterminate transaction")
                return false
            }
            val mutated = super<HostFilesystem>.mutate(operations)
            if (mutated) {
                activeRewrite = armedRewrite
                armedRewrite = null
                activeRewriteBefore = armedRewriteBefore
                armedRewriteBefore = null
            }
            return mutated
        }

        fun failNextWrites(path: String, count: Int) {
            require(count >= 0)
            failedWritesRemaining[path] = count
        }

        fun failNextWriteValue(path: String, value: String, count: Int) {
            require(count >= 0)
            failedWriteValuesRemaining[path to value] = count
        }

        fun failNextReads(path: String, count: Int) {
            require(count >= 0)
            failedReadsRemaining[path] = count
        }

        fun failNextChmods(path: String, mode: Int, count: Int) {
            require(count >= 0)
            failedChmodsRemaining[path to mode] = count
        }

        fun failNextMutationIndeterminately(afterOperations: Int) {
            require(afterOperations > 0)
            nextIndeterminateMutationPrefix = afterOperations
        }

        fun rewriteOnNextIndeterminateMutation(path: String, value: String, mode: Int? = null) {
            require(path in values)
            nextIndeterminateRewrite = IndeterminateRewrite(path, value, mode)
        }

        fun retainMutationFailure(failure: Throwable) {
            mutationFailure = failure
        }

        fun rewriteAfterNextBatchRead(
            path: String,
            value: String,
            reads: Int = 1,
            failNextModeAfterRewrite: Boolean = false,
        ) {
            require(path in values)
            require(reads > 0)
            armedRewrite = RewriteAfterBatch(path, value, reads, failNextModeAfterRewrite)
        }

        fun rewriteBeforeNextBatchRead(path: String, value: String, reads: Int = 1) {
            require(path in values)
            require(reads > 0)
            armedRewriteBefore = RewriteAfterBatch(path, value, reads)
        }

        fun rewriteAfterReads(
            path: String,
            value: String,
            reads: Int = 1,
            failNextModeAfterRewrite: Boolean = false,
        ) {
            require(path in values)
            require(reads > 0)
            activeRewrite = RewriteAfterBatch(path, value, reads, failNextModeAfterRewrite)
        }
    }
}
