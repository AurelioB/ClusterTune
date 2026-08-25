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
    fun `automatic steps lower maxima below live minimums without writing minimum nodes`() {
        val fixture = fixture(withGpu = true, cpuMin = 600, gpuMin = 600)
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        val applied = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 400, gpu = 300))

        assertEquals(HostAutoSessionStatus.ACTIVE, applied.status)
        assertEquals("600", fixture.fs.values["min"])
        assertEquals("600", fixture.fs.values["gmin"])
        assertEquals("400", fixture.fs.values["max"])
        assertEquals("300", fixture.fs.values["gmax"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") || it.startsWith("write:gmin=") })
        assertFalse(fixture.fs.operations.any { it.startsWith("chmod:min=") || it.startsWith("chmod:gmin=") })

        val stopped = fixture.controller.stop(session, started.hostEpoch)

        assertTrue(stopped.restorationComplete)
        assertEquals("600", fixture.fs.values["min"])
        assertEquals("600", fixture.fs.values["gmin"])
        assertEquals("800", fixture.fs.values["max"])
        assertEquals("900", fixture.fs.values["gmax"])
        assertFalse(fixture.fs.operations.any { it.startsWith("write:min=") || it.startsWith("write:gmin=") })
        assertFalse(fixture.fs.operations.any { it.startsWith("chmod:min=") || it.startsWith("chmod:gmin=") })
    }

    @Test
    fun `atomic baseline replaces temporary caps and stop restores persisted normal without minimum writes`() {
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
        assertEquals(
            HostAutoSessionStatus.ACTIVE,
            fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 600)).status,
        )
        assertEquals("600", fixture.fs.values["max"])
        assertEquals("600", fixture.fs.values["gmax"])

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
    fun `ceiling change during telemetry startup aborts before atomic baseline writes`() {
        val fixture = fixture()
        fixture.telemetry.onBegin = { fixture.fs.values["max"] = "700" }
        val operationsBeforeStart = fixture.fs.operations.size

        val unsupported = fixture.controller.start(
            AutoSessionRequest(
                packageName = "com.game",
                targetFps = 60,
                heartbeatTimeoutMs = 5_000,
                baseline = request(cpu = 800).copy(maximumsOnly = true),
            ),
        )

        assertEquals(HostAutoSessionStatus.UNSUPPORTED, unsupported.status)
        assertTrue(unsupported.message.orEmpty().contains("telemetry was starting"))
        assertEquals("700", fixture.fs.values["max"])
        assertFalse(fixture.fs.operations.drop(operationsBeforeStart).any { it.startsWith("write:max=") })
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
    fun `confirmed stock CPU baseline does not claim a later external fallback`() {
        val fixture = hiddenStockFixture()
        fixture.fs.rewriteAfterNextBatchRead(
            path = "max0",
            value = "800",
            reads = 2,
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
        fixture.fs.rewriteAfterNextBatchRead(
            path = "gmax",
            value = "900",
            reads = 3,
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
    fun `external maximum drift stops without fighting or overwriting it`() {
        val fixture = fixture()
        val started = fixture.controller.start(AutoSessionRequest("com.game", 60, 5_000))
        val session = requireNotNull(started.sessionId)

        fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))
        val mutationsAfterFirst = fixture.fs.batchMutations
        fixture.fs.values["max"] = "800"
        val operationsBeforeStop = fixture.fs.operations.size

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, request(cpu = 600))

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("external maximum changed"))
        assertEquals("800", fixture.fs.values["max"])
        assertEquals(420, fixture.fs.modes["max"])
        assertEquals(mutationsAfterFirst, fixture.fs.batchMutations)
        assertFalse(fixture.fs.operations.drop(operationsBeforeStop).any { it.startsWith("write:max=") })
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
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
        val restoreOperations = fixture.fs.operations.drop(operationsBeforeStop)
        assertFalse(restoreOperations.any { it.startsWith("write:max0=") })
        assertTrue(restoreOperations.contains("write:max1=1200"))
        assertTrue(restoreOperations.contains("write:gmax=900"))
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
        fixture.fs.rewriteAfterNextBatchRead("max0", "700")

        val stopped = fixture.controller.applyStep(session, started.hostEpoch, multiRequest())

        assertEquals(HostAutoSessionStatus.STOPPED, stopped.status)
        assertTrue(stopped.restorationComplete)
        assertTrue(stopped.message.orEmpty().contains("external maximum changed after apply"))
        assertEquals("700", fixture.fs.values["max0"])
        assertEquals("1200", fixture.fs.values["max1"])
        assertEquals("900", fixture.fs.values["gmax"])
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
    fun `session can trim and recover an exact hidden GPU checkpoint`() {
        val fs = FakeFs(
            values = mutableMapOf("min" to "200", "max" to "800", "gmin" to "300", "gmax" to "900"),
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

        val trimmed = controller.applyStep(session, started.hostEpoch, request(cpu = 800, gpu = 600))
        assertEquals(trimmed.toString(), HostAutoSessionStatus.ACTIVE, trimmed.status)
        val recovered = controller.applyStep(session, started.hostEpoch, request(cpu = 600, gpu = 900))
        assertEquals(recovered.toString(), HostAutoSessionStatus.ACTIVE, recovered.status)

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
        var onBegin: (() -> Unit)? = null
        val begunTargets = mutableListOf<Int>()
        override fun capabilities() = HostAutoCapabilities(true, true, false, hasGpu, false, false, "fake")
        override fun begin(packageName: String, targetFps: Int): Result<Unit> {
            beginCount++
            begunTargets += targetFps
            onBegin?.invoke()
            return beginFailure?.let(Result.Companion::failure) ?: Result.success(Unit)
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
        private var nextIndeterminateMutationPrefix: Int? = null
        private var nextIndeterminateRewrite: Pair<String, String>? = null
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
                nextIndeterminateRewrite?.let { (path, value) -> values[path] = value }
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

        fun failNextMutationIndeterminately(afterOperations: Int) {
            require(afterOperations > 0)
            nextIndeterminateMutationPrefix = afterOperations
        }

        fun rewriteOnNextIndeterminateMutation(path: String, value: String) {
            require(path in values)
            nextIndeterminateRewrite = path to value
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

        fun rewriteAfterReads(path: String, value: String, reads: Int = 1) {
            require(path in values)
            require(reads > 0)
            activeRewrite = RewriteAfterBatch(path, value, reads)
        }
    }
}
