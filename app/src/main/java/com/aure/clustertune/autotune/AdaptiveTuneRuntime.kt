package com.aure.clustertune.autotune

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide presentation and cancellation state shared by every AppContainer instance. */
data class AdaptiveTuneRuntimeState(
    val active: Boolean = false,
    /** Identifies one Auto Tune run for presentation-only telemetry consumers. */
    val sessionGeneration: Long? = null,
    val packageName: String? = null,
    val appLabel: String? = null,
    val targetFps: Int? = null,
    val status: AdaptiveTuneStatus? = null,
    val reason: AdaptiveTuneReason? = null,
    val measuredFps: Double? = null,
    val p95FrameTimeMillis: Double? = null,
    val cpuLoad: Map<Int, Double?> = emptyMap(),
    val cpuClockKHz: Map<Int, Long?> = emptyMap(),
    val gpuBusy: Double? = null,
    val gpuClockHz: Long? = null,
    val thermalMilliCelsius: Map<String, Long> = emptyMap(),
    val frameConfidence: Double? = null,
    val slowFrameRatio: Double? = null,
    val frameStale: Boolean = false,
    /** Monotonic identity of the latest published host sample. */
    val sampleTimestampNanos: Long? = null,
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
    private val lock = Any()
    private var generation = 0L
    private var coordinatorLeaseSequence = 0L
    private var currentCoordinatorLease: Long? = null
    private var coordinatorAcceptsStarts = false
    private val mutableState = MutableStateFlow(AdaptiveTuneRuntimeState())
    val state: StateFlow<AdaptiveTuneRuntimeState> = mutableState.asStateFlow()

    /** Atomically revokes an older coordinator and any generation it still owns. */
    fun claimCoordinatorLease(): Long = synchronized(lock) {
        val replacesExistingCoordinator = currentCoordinatorLease != null
        val lease = ++coordinatorLeaseSequence
        currentCoordinatorLease = lease
        coordinatorAcceptsStarts = true
        if (replacesExistingCoordinator) {
            invalidateLocked("App profile coordinator replaced")
        }
        lease
    }

    fun isCoordinatorCurrent(lease: Long): Boolean = synchronized(lock) {
        currentCoordinatorLease == lease
    }

    fun begin(packageName: String, appLabel: String, targetFps: Int): Long = synchronized(lock) {
        beginLocked(packageName, appLabel, targetFps, "Starting Auto Tune")
    }

    /** Atomically rejects a delayed start from a stopped or replaced coordinator. */
    fun beginIfCoordinatorCurrent(
        coordinatorLease: Long,
        packageName: String,
        appLabel: String,
        targetFps: Int,
    ): Long? = synchronized(lock) {
        if (currentCoordinatorLease != coordinatorLease || !coordinatorAcceptsStarts) {
            return@synchronized null
        }
        beginLocked(packageName, appLabel, targetFps, "Starting Auto Tune")
    }

    /** Starts a retry only if nothing invalidated the completed attempt. */
    fun beginIfCurrent(
        expectedGeneration: Long,
        packageName: String,
        appLabel: String,
        targetFps: Int,
        coordinatorLease: Long? = null,
    ): Long? = synchronized(lock) {
        if (generation != expectedGeneration ||
            coordinatorLease != null &&
            (currentCoordinatorLease != coordinatorLease || !coordinatorAcceptsStarts)
        ) {
            return@synchronized null
        }
        beginLocked(packageName, appLabel, targetFps, "Retrying Auto Tune")
    }

    private fun beginLocked(
        packageName: String,
        appLabel: String,
        targetFps: Int,
        message: String,
    ): Long {
        val token = ++generation
        mutableState.value = AdaptiveTuneRuntimeState(
            active = true,
            sessionGeneration = token,
            packageName = packageName,
            appLabel = appLabel,
            targetFps = targetFps,
            status = AdaptiveTuneStatus.WARMING_UP,
            reason = AdaptiveTuneReason.WARMUP,
            message = message,
        )
        return token
    }

    internal fun currentGeneration(): Long = synchronized(lock) { generation }

    fun isCurrent(token: Long): Boolean = synchronized(lock) { generation == token }

    fun publish(token: Long, transform: (AdaptiveTuneRuntimeState) -> AdaptiveTuneRuntimeState) {
        synchronized(lock) {
            if (generation == token) {
                mutableState.value = transform(mutableState.value)
            }
        }
    }

    /** Invalidates all outstanding automatic samples before a competing write can begin. */
    fun invalidate(message: String? = null): Long = synchronized(lock) {
        invalidateLocked(message)
    }

    /** Prevents a replaced coordinator from invalidating its successor's session. */
    fun invalidateIfCoordinatorCurrent(lease: Long, message: String? = null): Boolean = synchronized(lock) {
        if (currentCoordinatorLease != lease) return@synchronized false
        invalidateLocked(message)
        true
    }

    /** Stops future starts while retaining the lease for asynchronous exact-handle cleanup. */
    fun stopCoordinatorIfCurrent(lease: Long, message: String? = null): Boolean = synchronized(lock) {
        if (currentCoordinatorLease != lease) return@synchronized false
        coordinatorAcceptsStarts = false
        invalidateLocked(message)
        true
    }

    private fun invalidateLocked(message: String?): Long {
        val token = ++generation
        val current = mutableState.value
        if (current.active) {
            mutableState.value = current.copy(
                active = false,
                status = AdaptiveTuneStatus.STOPPED,
                reason = AdaptiveTuneReason.REQUESTED_STOP,
                message = message ?: current.message,
            )
        }
        return token
    }

    fun finish(token: Long, message: String? = null) {
        synchronized(lock) {
            if (generation != token) return
            mutableState.value = mutableState.value.copy(
                active = false,
                status = AdaptiveTuneStatus.STOPPED,
                message = message ?: mutableState.value.message,
            )
        }
    }

    /** Surfaces a cleanup failure without overwriting a newer active owner. */
    fun reportCleanupFailure(message: String) {
        synchronized(lock) {
            reportCleanupFailureLocked(message)
        }
    }

    fun reportCleanupFailureIfCoordinatorCurrent(lease: Long, message: String): Boolean = synchronized(lock) {
        if (currentCoordinatorLease != lease) return@synchronized false
        reportCleanupFailureLocked(message)
        true
    }

    private fun reportCleanupFailureLocked(message: String) {
        val current = mutableState.value
        if (current.active) return
        mutableState.value = current.copy(
            active = false,
            status = AdaptiveTuneStatus.STOPPED,
            message = message.trim().take(240).ifEmpty { "Auto Tune restoration failed" },
        )
    }

    /** Removes a terminal card once its app is no longer the automation owner. */
    fun clearInactive() {
        synchronized(lock) {
            clearInactiveLocked()
        }
    }

    fun clearInactiveIfCoordinatorCurrent(lease: Long): Boolean = synchronized(lock) {
        if (currentCoordinatorLease != lease) return@synchronized false
        clearInactiveLocked()
        true
    }

    private fun clearInactiveLocked() {
        if (!mutableState.value.active) {
            mutableState.value = AdaptiveTuneRuntimeState()
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            generation = 0L
            coordinatorLeaseSequence = 0L
            currentCoordinatorLease = null
            coordinatorAcceptsStarts = false
            mutableState.value = AdaptiveTuneRuntimeState()
        }
    }
}
