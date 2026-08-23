package com.aure.clustertune.autotune

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Process-wide presentation and cancellation state shared by every AppContainer instance. */
data class AdaptiveTuneRuntimeState(
    val active: Boolean = false,
    val packageName: String? = null,
    val appLabel: String? = null,
    val targetFps: Int? = null,
    val status: AdaptiveTuneStatus? = null,
    val reason: AdaptiveTuneReason? = null,
    val measuredFps: Double? = null,
    val p95FrameTimeMillis: Double? = null,
    val cpuLoad: Map<Int, Double?> = emptyMap(),
    val gpuBusy: Double? = null,
    val ceilings: AdaptiveFrequencyCeilings? = null,
    val frameBackend: String? = null,
    val message: String? = null,
)

/**
 * Global generation is the app-side half of write arbitration. A manual,
 * sleep, or fixed-profile request invalidates the generation before waiting
 * for the repository mutex, so a delayed automatic step fails closed.
 */
object AdaptiveTuneRuntime {
    private val generation = AtomicLong(0L)
    private val mutableState = MutableStateFlow(AdaptiveTuneRuntimeState())
    val state: StateFlow<AdaptiveTuneRuntimeState> = mutableState.asStateFlow()

    fun begin(packageName: String, appLabel: String, targetFps: Int): Long {
        val token = generation.incrementAndGet()
        mutableState.value = AdaptiveTuneRuntimeState(
            active = true,
            packageName = packageName,
            appLabel = appLabel,
            targetFps = targetFps,
            status = AdaptiveTuneStatus.WARMING_UP,
            reason = AdaptiveTuneReason.WARMUP,
            message = "Starting Auto Tune",
        )
        return token
    }

    fun isCurrent(token: Long): Boolean = generation.get() == token

    fun publish(token: Long, transform: (AdaptiveTuneRuntimeState) -> AdaptiveTuneRuntimeState) {
        if (!isCurrent(token)) return
        mutableState.update { current ->
            if (isCurrent(token)) transform(current) else current
        }
    }

    /** Invalidates all outstanding automatic samples before a competing write can begin. */
    fun invalidate(message: String? = null): Long {
        val token = generation.incrementAndGet()
        mutableState.update { current ->
            AdaptiveTuneRuntimeState(
                active = false,
                packageName = current.packageName,
                appLabel = current.appLabel,
                targetFps = current.targetFps,
                status = AdaptiveTuneStatus.STOPPED,
                reason = AdaptiveTuneReason.REQUESTED_STOP,
                measuredFps = current.measuredFps,
                p95FrameTimeMillis = current.p95FrameTimeMillis,
                cpuLoad = current.cpuLoad,
                gpuBusy = current.gpuBusy,
                ceilings = current.ceilings,
                frameBackend = current.frameBackend,
                message = message,
            )
        }
        return token
    }

    fun finish(token: Long, message: String? = null) {
        if (!isCurrent(token)) return
        mutableState.update { current ->
            current.copy(
                active = false,
                status = AdaptiveTuneStatus.STOPPED,
                message = message ?: current.message,
            )
        }
    }

    internal fun resetForTest() {
        generation.set(0L)
        mutableState.value = AdaptiveTuneRuntimeState()
    }
}
