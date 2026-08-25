package com.aure.clustertune.autotune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFrequencyControllerTest {
    @Test
    fun `every positive Int target is supported`() {
        listOf(1, 30, 60, 77, 120, Int.MAX_VALUE).forEach { target ->
            val controller = controller(targetFps = target, warmup = 0)

            assertEquals(mapOf(0 to 400L), controller.baseCeilings.cpuKHz)
            assertNull(controller.baseCeilings.gpuHz)
            val decision = controller.step(sample(1, frame(target.toDouble(), target), cpu0 = 0.2))

            assertApply(decision).also {
                assertEquals(AdaptiveTuneReason.EFFICIENCY_TRIM, it.reason)
                assertEquals(-1, it.change.stepDelta)
            }
        }

        assertThrows(IllegalArgumentException::class.java) { AdaptiveTuneConfig(targetFps = 0) }
        assertThrows(IllegalArgumentException::class.java) { AdaptiveTuneConfig(targetFps = -1) }
    }

    @Test
    fun `five samples warm up then three fresh healthy samples qualify a one OPP trim`() {
        val controller = controller(warmup = 5, qualification = 3)

        (1L..5L).forEach { second ->
            val decision = assertHold(controller.step(sample(second, frame(60.0), cpu0 = 0.15)))
            assertEquals(AdaptiveTuneStatus.WARMING_UP, decision.status)
            assertEquals(AdaptiveTuneReason.WARMUP, decision.reason)
            assertEquals((5L - second).toInt(), decision.warmupSamplesRemaining)
            assertEquals(400L, decision.ceilings.cpuKHz.getValue(0))
        }

        (6L..7L).forEach { second ->
            val qualifying = assertHold(controller.step(sample(second, frame(60.0), cpu0 = 0.15)))
            assertEquals(AdaptiveTuneStatus.MONITORING, qualifying.status)
            assertEquals(AdaptiveTuneReason.HEALTHY_QUALIFYING, qualifying.reason)
            assertEquals((8L - second).toInt(), qualifying.healthySamplesRemaining)
            assertEquals(400L, qualifying.ceilings.cpuKHz.getValue(0))
        }

        val trim = assertApply(controller.step(sample(8, frame(60.0), cpu0 = 0.15)))
        assertEquals(AdaptiveTuneStatus.OPTIMIZING, trim.status)
        assertEquals(AdaptiveTuneReason.EFFICIENCY_TRIM, trim.reason)
        assertEquals(AdaptiveActuator.CpuPolicy(0), trim.change.actuator)
        assertEquals(400L, trim.change.fromCeiling)
        assertEquals(300L, trim.change.toCeiling)
        assertEquals(-1, trim.change.stepDelta)
    }

    @Test
    fun `neutral sample resets the healthy qualification streak`() {
        val controller = controller(warmup = 0, qualification = 3)
        assertEquals(
            AdaptiveTuneReason.HEALTHY_QUALIFYING,
            assertHold(controller.step(sample(1, frame(60.0), cpu0 = 0.1))).reason,
        )
        assertEquals(
            AdaptiveTuneReason.WITHIN_TARGET_BAND,
            assertHold(
                controller.step(
                    sample(2, frame(fps = 60.0, p95 = 10.0, slow = 0.05), cpu0 = 0.1),
                ),
            ).reason,
        )
        assertEquals(
            2,
            assertHold(controller.step(sample(3, frame(60.0), cpu0 = 0.1))).healthySamplesRemaining,
        )
    }

    @Test
    fun `p95 and slow ratio independently trigger recovery at target fps`() {
        listOf(
            frame(fps = 60.0, p95 = 40.0, slow = 0.01),
            frame(fps = 60.0, p95 = 10.0, slow = 0.25),
        ).forEachIndexed { index, unhealthyFrame ->
            val controller = controller(warmup = 0)
            settleHealthyTrim(controller, startSecond = 1, cpuLoad = mapOf(0 to 0.1))

            val recovery = assertApply(
                controller.step(sample(4, unhealthyFrame, cpu0 = 0.95)),
            )

            assertEquals("metric case $index", AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY, recovery.reason)
            assertTrue("recovery must raise", recovery.change.stepDelta > 0)
        }
    }

    @Test
    fun `largest CPU load selects its policy for a three OPP recovery`() {
        val controller = AdaptiveFrequencyController(
            config = config(warmup = 0),
            envelope = AdaptiveTuneEnvelope(
                cpuPolicies = listOf(
                    cpu(0, 100, 200, 300, 400),
                    cpu(4, 100, 200, 300, 400),
                ),
            ),
        )

        listOf(1L, 4L, 7L).forEach { second ->
            settleHealthyTrim(
                controller,
                startSecond = second,
                cpuLoad = mapOf(0 to 0.1, 4 to 0.9),
            )
        }
        listOf(10L, 13L, 16L).forEach { second ->
            settleHealthyTrim(
                controller,
                startSecond = second,
                cpuLoad = mapOf(0 to 0.9, 4 to 0.1),
            )
        }
        assertEquals(mapOf(0 to 100L, 4 to 100L), controller.currentCeilings().cpuKHz)

        val before = controller.currentCeilings()
        val recovery = assertApply(
            controller.step(
                sample(19, frame(35.0), cpuLoad = mapOf(0 to 0.25, 4 to 0.96)),
            ),
        )

        assertEquals(AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY, recovery.reason)
        assertEquals(AdaptiveActuator.CpuPolicy(4), recovery.change.actuator)
        assertEquals(3, recovery.change.stepDelta)
        assertEquals(400L, recovery.ceilings.cpuKHz.getValue(4))
        assertEquals(100L, recovery.ceilings.cpuKHz.getValue(0))
        assertOneChangedDomain(before, recovery)
    }

    @Test
    fun `GPU busy selects GPU recovery while CPU ceiling remains unchanged`() {
        val controller = AdaptiveFrequencyController(
            config = config(warmup = 0),
            envelope = AdaptiveTuneEnvelope(
                cpuPolicies = listOf(cpu(0, 100, 200)),
                gpu = AdaptiveGpuDomain(
                    availableCeilingsHz = listOf(300_000_000, 600_000_000),
                    baseCeilingHz = 600_000_000,
                ),
            ),
        )
        settleHealthyTrim(
            controller,
            startSecond = 1,
            cpuLoad = mapOf(0 to 0.1),
            gpuBusy = 0.9,
        )
        settleHealthyTrim(
            controller,
            startSecond = 4,
            cpuLoad = mapOf(0 to 0.9),
            gpuBusy = 0.1,
        )
        val before = controller.currentCeilings()

        val recovery = assertApply(
            controller.step(
                sample(7, frame(40.0), cpu0 = 0.30, gpuBusy = 0.97),
            ),
        )

        assertEquals(AdaptiveTuneReason.GPU_BOTTLENECK_RECOVERY, recovery.reason)
        assertEquals(AdaptiveActuator.Gpu("gpu"), recovery.change.actuator)
        assertEquals(600_000_000L, recovery.ceilings.gpuHz)
        assertEquals(before.cpuKHz, recovery.ceilings.cpuKHz)
        assertOneChangedDomain(before, recovery)
    }

    @Test
    fun `trim trial watches then accepts sustained health`() {
        val controller = controller(warmup = 0)
        val trim = assertApply(controller.step(sample(1, frame(60.0), cpu0 = 0.1)))
        assertEquals(-1, trim.change.stepDelta)

        val watch = assertHold(controller.step(sample(2, frame(60.0), cpu0 = 0.1)))
        assertEquals(AdaptiveTuneStatus.WATCHING_TRIAL, watch.status)
        assertEquals(AdaptiveTuneReason.TRIAL_WATCH, watch.reason)

        val accepted = assertHold(controller.step(sample(3, frame(60.0), cpu0 = 0.1)))
        assertEquals(AdaptiveTuneStatus.MONITORING, accepted.status)
        assertEquals(AdaptiveTuneReason.TRIAL_ACCEPTED, accepted.reason)
        assertEquals(300L, accepted.ceilings.cpuKHz.getValue(0))
    }

    @Test
    fun `regressed trim is undone and frozen until its monotonic deadline`() {
        val controller = controller(warmup = 0, freezeNanos = 10 * SECOND)
        assertApply(controller.step(sample(1, frame(60.0), cpu0 = 0.1)))

        val rollback = assertApply(controller.step(sample(2, frame(45.0), cpu0 = 0.95)))
        assertEquals(AdaptiveTuneStatus.FROZEN, rollback.status)
        assertEquals(AdaptiveTuneReason.TRIAL_REGRESSION, rollback.reason)
        assertEquals(1, rollback.change.stepDelta)
        assertEquals(400L, rollback.ceilings.cpuKHz.getValue(0))

        val frozen = assertHold(controller.step(sample(3, frame(60.0), cpu0 = 0.1)))
        assertEquals(AdaptiveTuneStatus.FROZEN, frozen.status)
        assertEquals(AdaptiveTuneReason.HEALTHY_NO_TRIM_CANDIDATE, frozen.reason)

        val retry = assertApply(controller.step(sample(12, frame(60.0), cpu0 = 0.1)))
        assertEquals(AdaptiveTuneReason.EFFICIENCY_TRIM, retry.reason)
        assertEquals(-1, retry.change.stepDelta)
    }

    @Test
    fun `raise trial without gain rolls back and freezes only that recovery`() {
        val controller = controller(warmup = 0, freezeNanos = 10 * SECOND)
        settleHealthyTrim(controller, startSecond = 1, cpuLoad = mapOf(0 to 0.1))
        assertApply(controller.step(sample(4, frame(50.0), cpu0 = 0.95)))
        assertEquals(400L, controller.currentCeilings().cpuKHz.getValue(0))

        val watch = assertHold(controller.step(sample(5, frame(50.0), cpu0 = 0.95)))
        assertEquals(AdaptiveTuneReason.TRIAL_WATCH, watch.reason)
        val rollback = assertApply(controller.step(sample(6, frame(50.0), cpu0 = 0.95)))
        assertEquals(AdaptiveTuneReason.TRIAL_NO_GAIN, rollback.reason)
        assertEquals(-1, rollback.change.stepDelta)
        assertEquals(300L, rollback.ceilings.cpuKHz.getValue(0))

        val frozen = assertHold(controller.step(sample(7, frame(50.0), cpu0 = 0.95)))
        assertEquals(AdaptiveTuneStatus.FROZEN, frozen.status)
        assertEquals(AdaptiveTuneReason.RECOVERY_FROZEN, frozen.reason)

        val retry = assertApply(controller.step(sample(16, frame(50.0), cpu0 = 0.95)))
        assertEquals(AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY, retry.reason)
    }

    @Test
    fun `missing or explicitly stale frames stop after bounded grace`() {
        listOf<AdaptiveFrameMetrics?>(null, frame(60.0).copy(isStale = true)).forEach { missing ->
            val controller = controller(warmup = 0, graceNanos = 3 * SECOND)
            controller.step(sample(1, frame(60.0), cpu0 = 0.2))

            val waiting = assertHold(controller.step(sample(3, missing)))
            assertEquals(AdaptiveTuneStatus.WAITING_FOR_FRAMES, waiting.status)
            assertEquals(AdaptiveTuneReason.FRAME_DATA_GRACE, waiting.reason)

            val stop = assertStop(controller.step(sample(4, missing)))
            assertEquals(AdaptiveTuneReason.FRAME_DATA_STALE, stop.reason)
            val sticky = assertStop(controller.step(sample(5, frame(60.0))))
            assertEquals(AdaptiveTuneReason.FRAME_DATA_STALE, sticky.reason)
        }
    }

    @Test
    fun `sustained healthy and unhealthy telemetry keeps optimization active`() {
        val envelope = AdaptiveTuneEnvelope(
            cpuPolicies = listOf(cpu(0, 100, 200), cpu(4, 100, 200)),
            gpu = AdaptiveGpuDomain(
                availableCeilingsHz = listOf(300_000_000, 600_000_000),
                baseCeilingHz = 600_000_000,
            ),
        )
        val controller = AdaptiveFrequencyController(config(warmup = 0), envelope)

        val first = settleHealthyTrim(
            controller,
            startSecond = 1,
            cpuLoad = mapOf(0 to 0.1, 4 to 0.9),
            gpuBusy = 0.9,
        )
        val second = settleHealthyTrim(
            controller,
            startSecond = 4,
            cpuLoad = mapOf(0 to 0.9, 4 to 0.1),
            gpuBusy = 0.9,
        )
        val third = settleHealthyTrim(
            controller,
            startSecond = 7,
            cpuLoad = mapOf(0 to 0.9, 4 to 0.9),
            gpuBusy = 0.1,
        )
        assertEquals(AdaptiveActuator.CpuPolicy(0), first.change.actuator)
        assertEquals(AdaptiveActuator.CpuPolicy(4), second.change.actuator)
        assertEquals(AdaptiveActuator.Gpu("gpu"), third.change.actuator)
        assertEquals(AdaptiveTuneStatus.OPTIMIZING, first.status)
        assertEquals(AdaptiveTuneStatus.OPTIMIZING, second.status)
        assertEquals(AdaptiveTuneStatus.OPTIMIZING, third.status)

        val recovery = assertApply(
            controller.step(
                sample(
                    second = 10,
                    frames = frame(40.0),
                    cpuLoad = mapOf(0 to 0.1, 4 to 0.99),
                    gpuBusy = 0.1,
                ),
            ),
        )
        assertEquals(AdaptiveTuneStatus.RECOVERING, recovery.status)
        assertEquals(AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY, recovery.reason)
        assertEquals(AdaptiveActuator.CpuPolicy(4), recovery.change.actuator)
    }

    @Test
    fun `sparse and malformed frequency lists remain bounded by the base envelope`() {
        val sparse = AdaptiveFrequencyController(
            config(warmup = 0),
            AdaptiveTuneEnvelope(
                cpuPolicies = listOf(
                    AdaptiveCpuPolicy(
                        policyId = 0,
                        availableCeilingsKHz = listOf(0, -50, 700, 400, 400, 100),
                        baseCeilingKHz = 500,
                    ),
                ),
            ),
        )
        assertEquals(500L, sparse.currentCeilings().cpuKHz.getValue(0))
        assertEquals(400L, settleHealthyTrim(sparse, startSecond = 1).change.toCeiling)
        assertEquals(100L, settleHealthyTrim(sparse, startSecond = 4).change.toCeiling)
        assertEquals(
            AdaptiveTuneReason.HEALTHY_AT_FLOOR,
            assertHold(sparse.step(sample(7, frame(60.0)))).reason,
        )
        assertTrue(sparse.currentCeilings().cpuKHz.getValue(0) <= 500L)

        val empty = AdaptiveFrequencyController(
            config(warmup = 0),
            AdaptiveTuneEnvelope(
                cpuPolicies = listOf(AdaptiveCpuPolicy(0, emptyList(), baseCeilingKHz = 333)),
            ),
        )
        assertEquals(333L, empty.currentCeilings().cpuKHz.getValue(0))
        assertEquals(
            AdaptiveTuneReason.HEALTHY_AT_FLOOR,
            assertHold(empty.step(sample(1, frame(60.0)))).reason,
        )
    }

    @Test
    fun `every apply decision changes exactly one maximum-frequency domain`() {
        val controller = AdaptiveFrequencyController(
            config(warmup = 0),
            AdaptiveTuneEnvelope(
                cpuPolicies = listOf(cpu(0, 100, 200), cpu(4, 100, 200)),
                gpu = AdaptiveGpuDomain(availableCeilingsHz = listOf(300, 600), baseCeilingHz = 600),
            ),
        )
        val observations = listOf(
            Triple(mapOf(0 to 0.1, 4 to 0.9), 0.9, 1L),
            Triple(mapOf(0 to 0.9, 4 to 0.1), 0.9, 4L),
            Triple(mapOf(0 to 0.9, 4 to 0.9), 0.1, 7L),
        )
        var previous = controller.currentCeilings()
        observations.forEach { (cpuLoad, gpuBusy, second) ->
            val decision = settleHealthyTrim(
                controller,
                startSecond = second,
                cpuLoad = cpuLoad,
                gpuBusy = gpuBusy,
            )
            assertOneChangedDomain(previous, decision)
            previous = decision.ceilings
        }

        val recovery = assertApply(
            controller.step(
                sample(
                    second = 10,
                    frames = frame(40.0),
                    cpuLoad = mapOf(0 to 0.2, 4 to 0.3),
                    gpuBusy = 0.99,
                ),
            ),
        )
        assertOneChangedDomain(previous, recovery)
    }

    private fun settleHealthyTrim(
        controller: AdaptiveFrequencyController,
        startSecond: Long,
        cpuLoad: Map<Int, Double?> = emptyMap(),
        gpuBusy: Double? = null,
    ): AdaptiveTuneDecision.Apply {
        val trim = assertApply(
            controller.step(sample(startSecond, frame(60.0), cpuLoad = cpuLoad, gpuBusy = gpuBusy)),
        )
        assertEquals(AdaptiveTuneReason.EFFICIENCY_TRIM, trim.reason)
        assertEquals(
            AdaptiveTuneReason.TRIAL_WATCH,
            assertHold(
                controller.step(
                    sample(startSecond + 1, frame(60.0), cpuLoad = cpuLoad, gpuBusy = gpuBusy),
                ),
            ).reason,
        )
        assertEquals(
            AdaptiveTuneReason.TRIAL_ACCEPTED,
            assertHold(
                controller.step(
                    sample(startSecond + 2, frame(60.0), cpuLoad = cpuLoad, gpuBusy = gpuBusy),
                ),
            ).reason,
        )
        return trim
    }

    private fun controller(
        targetFps: Int = 60,
        warmup: Int = 5,
        qualification: Int = 1,
        graceNanos: Long = 4 * SECOND,
        freezeNanos: Long = 30 * SECOND,
    ): AdaptiveFrequencyController = AdaptiveFrequencyController(
        config = config(targetFps, warmup, qualification, graceNanos, freezeNanos),
        envelope = AdaptiveTuneEnvelope(cpuPolicies = listOf(cpu(0, 100, 200, 300, 400))),
    )

    private fun config(
        targetFps: Int = 60,
        warmup: Int = 5,
        qualification: Int = 1,
        graceNanos: Long = 4 * SECOND,
        freezeNanos: Long = 30 * SECOND,
    ) = AdaptiveTuneConfig(
        targetFps = targetFps,
        warmupSampleCount = warmup,
        healthyQualificationSampleCount = qualification,
        missingFrameGraceNanos = graceNanos,
        trialWatchSampleCount = 2,
        trialFreezeNanos = freezeNanos,
    )

    private fun cpu(policyId: Int, vararg frequencies: Long): AdaptiveCpuPolicy =
        AdaptiveCpuPolicy(
            policyId = policyId,
            availableCeilingsKHz = frequencies.toList(),
            baseCeilingKHz = frequencies.last(),
        )

    private fun frame(
        fps: Double,
        targetFps: Int = 60,
        p95: Double = 1_000.0 / targetFps,
        slow: Double = 0.01,
    ) = AdaptiveFrameMetrics(fps, p95, slow)

    private fun sample(
        second: Long,
        frames: AdaptiveFrameMetrics?,
        cpu0: Double? = null,
        cpuLoad: Map<Int, Double?> = if (cpu0 == null) emptyMap() else mapOf(0 to cpu0),
        gpuBusy: Double? = null,
    ) = AdaptiveTuneSample(
        timestampNanos = second * SECOND,
        frames = frames,
        cpuLoad = cpuLoad,
        gpuBusy = gpuBusy,
    )

    private fun assertOneChangedDomain(
        before: AdaptiveFrequencyCeilings,
        decision: AdaptiveTuneDecision.Apply,
    ) {
        val cpuChanges = (before.cpuKHz.keys + decision.ceilings.cpuKHz.keys)
            .count { before.cpuKHz[it] != decision.ceilings.cpuKHz[it] }
        val gpuChanges = if (before.gpuHz != decision.ceilings.gpuHz) 1 else 0
        assertEquals(1, cpuChanges + gpuChanges)
        assertTrue(decision.change.stepDelta != 0)
    }

    private fun assertApply(decision: AdaptiveTuneDecision): AdaptiveTuneDecision.Apply {
        assertTrue("Expected Apply but was $decision", decision is AdaptiveTuneDecision.Apply)
        return decision as AdaptiveTuneDecision.Apply
    }

    private fun assertHold(decision: AdaptiveTuneDecision): AdaptiveTuneDecision.Hold {
        assertTrue("Expected Hold but was $decision", decision is AdaptiveTuneDecision.Hold)
        return decision as AdaptiveTuneDecision.Hold
    }

    private fun assertStop(decision: AdaptiveTuneDecision): AdaptiveTuneDecision.Stop {
        assertTrue("Expected Stop but was $decision", decision is AdaptiveTuneDecision.Stop)
        return decision as AdaptiveTuneDecision.Stop
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
