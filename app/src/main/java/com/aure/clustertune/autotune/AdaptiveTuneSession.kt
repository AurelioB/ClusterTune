package com.aure.clustertune.autotune

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class AdaptiveTuneBackendSession(
    val sessionId: String,
    val hostEpoch: Long,
    val generation: Long,
    val envelope: AdaptiveTuneEnvelope,
    val frameBackend: String? = null,
)

data class AdaptiveTuneObservation(
    val sample: AdaptiveTuneSample,
    val frameBackend: String? = null,
)

enum class AdaptiveTuneTermination {
    STOPPED,
    FAILED,
    SUPERSEDED,
}

data class AdaptiveTuneRunResult(
    val generation: Long,
    val sessionStarted: Boolean,
    val termination: AdaptiveTuneTermination,
    val message: String? = null,
)

class AdaptiveTuneSupersededException(
    message: String = "Auto Tune was superseded",
) : IllegalStateException(message)

/** Typed hardware/telemetry boundary used by the coroutine session runner. */
interface AdaptiveTuneBackend {
    suspend fun start(
        packageName: String,
        targetFps: Int,
        generation: Long,
    ): AdaptiveTuneBackendSession

    suspend fun heartbeat(session: AdaptiveTuneBackendSession)

    suspend fun sample(
        session: AdaptiveTuneBackendSession,
        afterSequence: Long,
    ): Pair<Long, AdaptiveTuneObservation>

    suspend fun apply(
        session: AdaptiveTuneBackendSession,
        ceilings: AdaptiveFrequencyCeilings,
    )

    /** Must be idempotent and restore the exact pre-session checkpoint. */
    suspend fun stop(session: AdaptiveTuneBackendSession)
}

/**
 * Runs one focused package until cancellation, stale telemetry, or a competing
 * manual/sleep/profile request invalidates its generation.
 */
class AdaptiveTuneSessionRunner(
    private val backend: AdaptiveTuneBackend,
    private val sampleIntervalMillis: Long = 1_000L,
    private val backendCallTimeoutMillis: Long = 10_000L,
    private val restorationTimeoutMillis: Long = 5_000L,
) {
    private data class CompletedBackendCall<T>(val value: T)

    init {
        require(sampleIntervalMillis in 100L..10_000L) { "Invalid Auto Tune sample interval" }
        require(backendCallTimeoutMillis in 100L..60_000L) { "Invalid Auto Tune backend timeout" }
        require(restorationTimeoutMillis in 100L..60_000L) { "Invalid Auto Tune restoration timeout" }
    }

    suspend fun run(
        packageName: String,
        appLabel: String,
        targetFps: Int,
        retryAfterGeneration: Long? = null,
        coordinatorLease: Long? = null,
        onSessionStarted: suspend () -> Unit = {},
    ): AdaptiveTuneRunResult {
        currentCoroutineContext().ensureActive()
        val generation = if (retryAfterGeneration == null) {
            if (coordinatorLease == null) {
                AdaptiveTuneRuntime.begin(packageName, appLabel, targetFps)
            } else {
                AdaptiveTuneRuntime.beginIfCoordinatorCurrent(
                    coordinatorLease = coordinatorLease,
                    packageName = packageName,
                    appLabel = appLabel,
                    targetFps = targetFps,
                ) ?: return AdaptiveTuneRunResult(
                    generation = AdaptiveTuneRuntime.currentGeneration(),
                    sessionStarted = false,
                    termination = AdaptiveTuneTermination.SUPERSEDED,
                )
            }
        } else {
            AdaptiveTuneRuntime.beginIfCurrent(
                expectedGeneration = retryAfterGeneration,
                packageName = packageName,
                appLabel = appLabel,
                targetFps = targetFps,
                coordinatorLease = coordinatorLease,
            ) ?: return AdaptiveTuneRunResult(
                generation = retryAfterGeneration,
                sessionStarted = false,
                termination = AdaptiveTuneTermination.SUPERSEDED,
            )
        }
        var session: AdaptiveTuneBackendSession? = null
        var sessionStarted = false
        var lastSequence = -1L
        var terminalMessage: String? = null
        var outcomeMessage: String? = null
        var termination = AdaptiveTuneTermination.SUPERSEDED
        try {
            session = callBackend("start") { backend.start(packageName, targetFps, generation) }
            sessionStarted = true
            check(session.generation == generation) { "Auto Tune backend generation mismatch" }
            if (!AdaptiveTuneRuntime.isCurrent(generation)) {
                throw AdaptiveTuneSupersededException("Auto Tune was superseded during startup")
            }
            onSessionStarted()
            if (!AdaptiveTuneRuntime.isCurrent(generation)) {
                throw AdaptiveTuneSupersededException("Auto Tune was superseded during startup")
            }
            val controller = AdaptiveFrequencyController(
                config = AdaptiveTuneConfig(targetFps = targetFps),
                envelope = session.envelope,
            )
            AdaptiveTuneRuntime.publish(generation) { state ->
                state.copy(
                    ceilings = controller.currentCeilings(),
                    frameBackend = session.frameBackend,
                    message = "Waiting for frame telemetry",
                )
            }

            while (AdaptiveTuneRuntime.isCurrent(generation)) {
                currentCoroutineContext().ensureActive()
                callBackend("heartbeat") { backend.heartbeat(session) }
                if (!AdaptiveTuneRuntime.isCurrent(generation)) break

                val (sequence, observation) = callBackend("sample") {
                    backend.sample(session, lastSequence)
                }
                if (!AdaptiveTuneRuntime.isCurrent(generation)) break
                check(sequence > lastSequence) { "Auto Tune telemetry did not advance" }
                lastSequence = sequence
                val decision = controller.step(observation.sample)
                when (decision) {
                    is AdaptiveTuneDecision.Apply -> {
                        if (!AdaptiveTuneRuntime.isCurrent(generation)) break
                        callBackend("apply") { backend.apply(session, decision.ceilings) }
                        publishDecision(generation, decision, observation)
                    }
                    is AdaptiveTuneDecision.Stop -> {
                        publishDecision(generation, decision, observation)
                        terminalMessage = decision.reason.userMessage()
                        termination = AdaptiveTuneTermination.STOPPED
                        break
                    }
                    is AdaptiveTuneDecision.Hold -> publishDecision(generation, decision, observation)
                }
                if (!AdaptiveTuneRuntime.isCurrent(generation)) break
                delay(sampleIntervalMillis)
            }
        } catch (cancelled: CancellationException) {
            terminalMessage = "Auto Tune stopped"
            throw cancelled
        } catch (_: AdaptiveTuneSupersededException) {
            termination = AdaptiveTuneTermination.SUPERSEDED
            terminalMessage = null
        } catch (failure: Throwable) {
            termination = AdaptiveTuneTermination.FAILED
            terminalMessage = failure.message?.takeIf(String::isNotBlank) ?: "Auto Tune stopped unexpectedly"
            AdaptiveTuneRuntime.publish(generation) { state ->
                state.copy(
                    active = false,
                    status = AdaptiveTuneStatus.STOPPED,
                    message = terminalMessage,
                )
            }
        } finally {
            val activeSession = session
            val restorationFailure = activeSession?.let { restoreSession(it) }
            val finalMessage = restorationFailure?.let {
                termination = AdaptiveTuneTermination.FAILED
                val detail = it.message?.takeIf(String::isNotBlank) ?: it.javaClass.simpleName
                "Auto Tune restoration failed: $detail".take(240)
            } ?: terminalMessage
            outcomeMessage = finalMessage
            AdaptiveTuneRuntime.finish(generation, finalMessage)
        }
        if (!AdaptiveTuneRuntime.isCurrent(generation)) {
            termination = AdaptiveTuneTermination.SUPERSEDED
        }
        return AdaptiveTuneRunResult(
            generation = generation,
            sessionStarted = sessionStarted,
            termination = termination,
            message = outcomeMessage,
        )
    }

    private suspend fun <T> callBackend(
        operation: String,
        block: suspend () -> T,
    ): T {
        // withTimeoutOrNull distinguishes this timeout from cancellation by an enclosing scope.
        // The wrapper also preserves a legitimate null value returned by a backend implementation.
        val completed = withTimeoutOrNull(backendCallTimeoutMillis) {
            CompletedBackendCall(block())
        }
        return completed?.value ?: throw IllegalStateException(
            "Auto Tune backend $operation timed out after ${backendCallTimeoutMillis}ms",
        )
    }

    private suspend fun restoreSession(session: AdaptiveTuneBackendSession): Throwable? =
        withContext(NonCancellable) {
            runCatching {
                withTimeout(restorationTimeoutMillis) { backend.stop(session) }
            }.exceptionOrNull()
        }

    private fun publishDecision(
        generation: Long,
        decision: AdaptiveTuneDecision,
        observation: AdaptiveTuneObservation,
    ) {
        val frames = observation.sample.frames
        AdaptiveTuneRuntime.publish(generation) { state ->
            state.copy(
                active = decision !is AdaptiveTuneDecision.Stop,
                status = decision.status,
                reason = decision.reason,
                measuredFps = frames?.fps,
                p95FrameTimeMillis = frames?.p95FrameTimeMillis,
                cpuLoad = observation.sample.cpuLoad,
                gpuBusy = observation.sample.gpuBusy,
                ceilings = decision.ceilings,
                frameBackend = observation.frameBackend ?: state.frameBackend,
                message = decision.reason.userMessage(),
            )
        }
    }
}

internal fun AdaptiveTuneReason.userMessage(): String = when (this) {
    AdaptiveTuneReason.WARMUP -> "Warming up frame telemetry"
    AdaptiveTuneReason.HEALTHY_QUALIFYING -> "Confirming performance headroom"
    AdaptiveTuneReason.FRAME_DATA_GRACE -> "Waiting for fresh frames"
    AdaptiveTuneReason.FRAME_DATA_STALE -> "Frame telemetry became unavailable"
    AdaptiveTuneReason.NON_MONOTONIC_SAMPLE -> "Frame telemetry clock was reset"
    AdaptiveTuneReason.HEALTHY_AT_FLOOR -> "Target is stable at the lowest available ceiling"
    AdaptiveTuneReason.HEALTHY_NO_TRIM_CANDIDATE -> "All lower ceilings are temporarily frozen"
    AdaptiveTuneReason.WITHIN_TARGET_BAND -> "Holding the current ceilings"
    AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY -> "Raising a CPU ceiling"
    AdaptiveTuneReason.GPU_BOTTLENECK_RECOVERY -> "Raising the GPU ceiling"
    AdaptiveTuneReason.RECOVERY_AT_BASE -> "Target needs the full configured envelope"
    AdaptiveTuneReason.RECOVERY_FROZEN -> "Recovery candidate is temporarily frozen"
    AdaptiveTuneReason.EFFICIENCY_TRIM -> "Testing a lower frequency ceiling"
    AdaptiveTuneReason.TRIAL_WATCH -> "Watching the last ceiling change"
    AdaptiveTuneReason.TRIAL_ACCEPTED -> "Last ceiling change is stable"
    AdaptiveTuneReason.TRIAL_REGRESSION -> "Reverted a ceiling that hurt frame pacing"
    AdaptiveTuneReason.TRIAL_NO_GAIN -> "Reverted a ceiling that did not improve performance"
    AdaptiveTuneReason.MODERATE_THERMAL_RAISE_BLOCKED -> "Thermal pressure is blocking a frequency raise"
    AdaptiveTuneReason.SEVERE_THERMAL_DOWNSHIFT -> "Reducing a ceiling under severe thermal pressure"
    AdaptiveTuneReason.SEVERE_THERMAL_AT_FLOOR -> "All ceilings are at their thermal safety floor"
    AdaptiveTuneReason.REQUESTED_STOP -> "Auto Tune stopped"
}
