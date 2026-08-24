package com.aure.clustertune.autotune

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveTuneSessionRunnerTest {
    @After
    fun tearDown() = AdaptiveTuneRuntime.resetForTest()

    @Test
    fun `stale telemetry stops and restores the backend`() = runTest {
        val backend = FakeBackend(
            samples = buildList {
                repeat(5) { index -> add(telemetry(index + 1L, stale = false)) }
                add(telemetry(10L, stale = true))
            },
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)
        var startedCallbacks = 0

        val result = runner.run("game.app", "Game", 60) { startedCallbacks += 1 }

        assertEquals(AdaptiveTuneTermination.STOPPED, result.termination)
        assertTrue(result.sessionStarted)
        assertEquals(1, startedCallbacks)
        assertEquals(1, backend.stopCalls)
        assertTrue(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals(AdaptiveTuneReason.FRAME_DATA_STALE, AdaptiveTuneRuntime.state.value.reason)
    }

    @Test
    fun `cancellation waits for a suspending restore in a non cancellable finalizer`() = runTest {
        val backend = FakeBackend(
            samples = List(20) { telemetry(it + 1L, stale = false) },
            stopDelayMillis = 500L,
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)
        val job = launch { runner.run("game.app", "Game", 120) }

        advanceTimeBy(250L)
        runCurrent()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, backend.stopCalls)
        assertTrue(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
    }

    @Test
    fun `caller timeout remains cancellation while finally still restores`() = runTest {
        val backend = FakeBackend(hangSampleAt = 1)
        val runner = AdaptiveTuneSessionRunner(
            backend = backend,
            sampleIntervalMillis = 100L,
            backendCallTimeoutMillis = 1_000L,
            restorationTimeoutMillis = 100L,
        )
        var failure: Throwable? = null

        try {
            withTimeout(100L) { runner.run("game.app", "Game", 60) }
        } catch (caught: Throwable) {
            failure = caught
        }

        assertTrue(failure is TimeoutCancellationException)
        assertEquals(1, backend.stopCalls)
        assertTrue(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
    }

    @Test
    fun `invalidation during sample prevents the pending hardware apply`() = runTest {
        val backend = FakeBackend(
            samples = List(8) { telemetry(it + 1L, stale = false) },
            onSample = { call ->
                if (call == 8) AdaptiveTuneRuntime.invalidate("Manual profile selected")
            },
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        val result = runner.run("game.app", "Game", 30)

        assertEquals(AdaptiveTuneTermination.SUPERSEDED, result.termination)
        assertEquals(0, backend.applyCalls)
        assertEquals(1, backend.stopCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("Manual profile selected", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `invalidation during restoration supersedes an already stopped decision`() = runTest {
        val backend = FakeBackend(
            samples = buildList {
                repeat(5) { index -> add(telemetry(index + 1L, stale = false)) }
                add(telemetry(10L, stale = true))
            },
            onStop = { AdaptiveTuneRuntime.invalidate("Sleep profile selected") },
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        val result = runner.run("game.app", "Game", 60)

        assertEquals(AdaptiveTuneTermination.SUPERSEDED, result.termination)
        assertEquals(1, backend.stopCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
    }

    @Test
    fun `invalidation between startup attempts prevents retry from creating a new generation`() = runTest {
        val backend = FakeBackend(startFailure = IllegalStateException("surface layer unavailable"))
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)
        val first = runner.run("game.app", "Game", 60)
        AdaptiveTuneRuntime.invalidate("Manual profile selected")

        val retry = runner.run(
            packageName = "game.app",
            appLabel = "Game",
            targetFps = 60,
            retryAfterGeneration = first.generation,
        )

        assertEquals(AdaptiveTuneTermination.SUPERSEDED, retry.termination)
        assertEquals(1, backend.startCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
    }

    @Test
    fun `replaced coordinator cannot begin a delayed published job`() = runTest {
        val backend = FakeBackend()
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)
        val oldLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        val newLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        val newGeneration = requireNotNull(
            AdaptiveTuneRuntime.beginIfCoordinatorCurrent(
                coordinatorLease = newLease,
                packageName = "new.game",
                appLabel = "New game",
                targetFps = 120,
            ),
        )

        val stale = runner.run(
            packageName = "old.game",
            appLabel = "Old game",
            targetFps = 60,
            coordinatorLease = oldLease,
        )

        assertEquals(AdaptiveTuneTermination.SUPERSEDED, stale.termination)
        assertFalse(stale.sessionStarted)
        assertEquals(0, backend.startCalls)
        assertTrue(AdaptiveTuneRuntime.isCurrent(newGeneration))
        assertEquals("new.game", AdaptiveTuneRuntime.state.value.packageName)
    }

    @Test
    fun `sample sequence must strictly advance and is passed back to backend`() = runTest {
        val backend = FakeBackend(
            samples = listOf(
                telemetry(sequence = 7L, second = 1L, stale = false),
                telemetry(sequence = 7L, second = 2L, stale = false),
            ),
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        runner.run("game.app", "Game", 60)

        assertEquals(listOf(-1L, 7L), backend.afterSequences)
        assertEquals(1, backend.stopCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("Auto Tune telemetry did not advance", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `backend session generation mismatch is rejected and restored`() = runTest {
        val backend = FakeBackend(returnedGeneration = { it + 1L })
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        runner.run("game.app", "Game", 60)

        assertEquals(0, backend.heartbeatCalls)
        assertEquals(0, backend.sampleCalls)
        assertEquals(1, backend.stopCalls)
        assertEquals("Auto Tune backend generation mismatch", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `start error terminates without attempting to restore an unknown session`() = runTest {
        val backend = FakeBackend(startFailure = IllegalStateException("start broke"))
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)
        var startedCallbacks = 0

        val result = runner.run("game.app", "Game", 60) { startedCallbacks += 1 }

        assertEquals(AdaptiveTuneTermination.FAILED, result.termination)
        assertFalse(result.sessionStarted)
        assertEquals(0, startedCallbacks)
        assertEquals(0, backend.stopCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals(AdaptiveTuneStatus.STOPPED, AdaptiveTuneRuntime.state.value.status)
        assertEquals("start broke", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `apply error terminates and restores the session`() = runTest {
        val backend = FakeBackend(
            samples = List(8) { telemetry(it + 1L, stale = false) },
            applyFailure = IllegalStateException("apply broke"),
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        runner.run("game.app", "Game", 60)

        assertEquals(1, backend.applyCalls)
        assertEquals(1, backend.stopCalls)
        assertTrue(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("apply broke", AdaptiveTuneRuntime.state.value.message)
        assertEquals(
            AdaptiveFrequencyCeilings(cpuKHz = mapOf(0 to 200L)),
            AdaptiveTuneRuntime.state.value.ceilings,
        )
    }

    @Test
    fun `restoration error replaces the terminal message`() = runTest {
        val backend = FakeBackend(
            samples = listOf(
                telemetry(1L, stale = false),
                telemetry(5L, stale = true),
            ),
            stopFailure = IllegalStateException("restore broke"),
        )
        val runner = AdaptiveTuneSessionRunner(backend, sampleIntervalMillis = 100L)

        runner.run("game.app", "Game", 60)

        assertEquals(1, backend.stopCalls)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals(
            "Auto Tune restoration failed: restore broke",
            AdaptiveTuneRuntime.state.value.message,
        )
    }

    @Test
    fun `stalled sample terminates at backend timeout under virtual time`() = runTest {
        val backend = FakeBackend(hangSampleAt = 1)
        val runner = AdaptiveTuneSessionRunner(
            backend = backend,
            sampleIntervalMillis = 100L,
            backendCallTimeoutMillis = 100L,
            restorationTimeoutMillis = 100L,
        )

        runner.run("game.app", "Game", 60)

        assertEquals(1, backend.sampleCalls)
        assertEquals(1, backend.stopCalls)
        assertTrue(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals(
            "Auto Tune backend sample timed out after 100ms",
            AdaptiveTuneRuntime.state.value.message,
        )
    }

    @Test
    fun `stalled restoration terminates at finalizer timeout under virtual time`() = runTest {
        val backend = FakeBackend(
            samples = listOf(
                telemetry(1L, stale = false),
                telemetry(5L, stale = true),
            ),
            hangOnStop = true,
        )
        val runner = AdaptiveTuneSessionRunner(
            backend = backend,
            sampleIntervalMillis = 100L,
            backendCallTimeoutMillis = 100L,
            restorationTimeoutMillis = 100L,
        )

        runner.run("game.app", "Game", 60)

        assertEquals(1, backend.stopCalls)
        assertFalse(backend.stopCompleted)
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertTrue(
            AdaptiveTuneRuntime.state.value.message
                .orEmpty()
                .startsWith("Auto Tune restoration failed:"),
        )
    }

    private class FakeBackend(
        private val samples: List<Pair<Long, AdaptiveTuneObservation>> = emptyList(),
        private val startFailure: Throwable? = null,
        private val applyFailure: Throwable? = null,
        private val stopFailure: Throwable? = null,
        private val returnedGeneration: (Long) -> Long = { it },
        private val onSample: (Int) -> Unit = {},
        private val hangSampleAt: Int? = null,
        private val stopDelayMillis: Long = 0L,
        private val hangOnStop: Boolean = false,
        private val onStop: () -> Unit = {},
    ) : AdaptiveTuneBackend {
        var startCalls = 0
            private set
        var heartbeatCalls = 0
            private set
        var sampleCalls = 0
            private set
        var applyCalls = 0
            private set
        var stopCalls = 0
            private set
        var stopCompleted = false
            private set
        val afterSequences = mutableListOf<Long>()
        private var sampleIndex = 0

        override suspend fun start(
            packageName: String,
            targetFps: Int,
            generation: Long,
        ): AdaptiveTuneBackendSession {
            startCalls += 1
            startFailure?.let { throw it }
            return AdaptiveTuneBackendSession(
                sessionId = "session",
                hostEpoch = 1L,
                generation = returnedGeneration(generation),
                envelope = AdaptiveTuneEnvelope(
                    cpuPolicies = listOf(
                        AdaptiveCpuPolicy(0, listOf(100L, 200L), baseCeilingKHz = 200L),
                    ),
                ),
                frameBackend = "fake",
            )
        }

        override suspend fun heartbeat(session: AdaptiveTuneBackendSession) {
            heartbeatCalls += 1
        }

        override suspend fun sample(
            session: AdaptiveTuneBackendSession,
            afterSequence: Long,
        ): Pair<Long, AdaptiveTuneObservation> {
            sampleCalls += 1
            afterSequences += afterSequence
            if (hangSampleAt == sampleCalls) awaitCancellation()
            onSample(sampleCalls)
            val index = sampleIndex++
            return samples.getOrElse(index) {
                telemetry(sequence = index + 100L, second = index + 100L, stale = true)
            }
        }

        override suspend fun apply(
            session: AdaptiveTuneBackendSession,
            ceilings: AdaptiveFrequencyCeilings,
        ) {
            applyCalls += 1
            applyFailure?.let { throw it }
        }

        override suspend fun stop(session: AdaptiveTuneBackendSession) {
            stopCalls += 1
            onStop()
            if (hangOnStop) awaitCancellation()
            if (stopDelayMillis > 0L) delay(stopDelayMillis)
            stopFailure?.let { throw it }
            stopCompleted = true
        }
    }

    private companion object {
        private const val SECOND = 1_000_000_000L

        fun telemetry(second: Long, stale: Boolean): Pair<Long, AdaptiveTuneObservation> =
            telemetry(sequence = second, second = second, stale = stale)

        fun telemetry(
            sequence: Long,
            second: Long,
            stale: Boolean,
        ): Pair<Long, AdaptiveTuneObservation> = sequence to AdaptiveTuneObservation(
            AdaptiveTuneSample(
                timestampNanos = second * SECOND,
                frames = AdaptiveFrameMetrics(
                    fps = 60.0,
                    p95FrameTimeMillis = 16.0,
                    slowFrameRatio = 0.01,
                    isStale = stale,
                ),
                cpuLoad = mapOf(0 to 0.2),
            ),
            frameBackend = "fake",
        )
    }
}
