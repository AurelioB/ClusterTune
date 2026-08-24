package com.aure.clustertune.root.host

/** Private wire contract between ClusterTune and its persistent privileged host. */
object HostProtocol {
    const val DESCRIPTOR = "com.aure.clustertune.root.host.IClusterTuneHost"
    const val VERSION = 8
    const val SERVICE_PREFIX = "clustertune.host."
    const val PING = 1
    const val HOST_IDENTITY = 2
    const val READ_CAPABILITIES = 3
    const val READ_STATE = 4
    const val APPLY_PROFILE = 5
    const val STOP = 7
    const val READ_SNAPSHOT = 9
    const val LEASE = 10
    const val READ_AUTO_CAPABILITIES = 11
    const val START_AUTO_SESSION = 12
    const val READ_AUTO_TELEMETRY = 13
    const val APPLY_AUTO_STEP = 14
    const val HEARTBEAT_AUTO_SESSION = 15
    const val STOP_AUTO_SESSION = 16

    const val MAX_PACKAGE_LENGTH = 255
    const val MAX_SESSION_ID_LENGTH = 64
    const val MAX_METADATA_LENGTH = 512
    const val MAX_THERMAL_READINGS = 32
    const val MAX_UNSUPPORTED_METRICS = 16
}

data class CpuDomain(
    val id: String,
    val minPath: String,
    val maxPath: String,
    val curPath: String?,
    val minimumCandidates: List<Long>,
    val supportedFrequencies: List<Long>,
    val stockMax: Long,
    val observedMax: Long,
    val observedMin: Long,
    val selectableMax: Long = stockMax,
    val currentMax: Long = observedMax,
)

data class GpuDomain(
    val id: String,
    val minPath: String?,
    val maxPath: String,
    val curPath: String?,
    val supportedFrequencies: List<Long> = emptyList(),
    val stockMax: Long = 0L,
    val observedMax: Long = 0L,
    val observedMin: Long = 0L,
    val selectableMax: Long = stockMax,
    val currentMax: Long = observedMax,
)

data class HostCapabilities(val cpus: List<CpuDomain>, val gpu: GpuDomain?)
data class HostState(
    val cpuMax: List<Long>,
    val cpuMin: List<Long> = emptyList(),
    val cpuCurrent: List<Long> = emptyList(),
    val gpuMax: Long?,
    val gpuMin: Long? = null,
    val gpuCurrent: Long? = null,
)

data class HostSnapshot(val capabilities: HostCapabilities, val state: HostState, val epoch: Long)

data class ApplyRequest(
    val cpuMax: List<Long>,
    val gpuMax: Long?,
    val resetToStock: Boolean,
    val cpuIds: List<String> = emptyList(),
    val gpuId: String? = null,
    val gpuMaxPath: String? = null,
    val stabilizedStockCeiling: Long? = null,
)

enum class HostApplyPhase { PREFLIGHT, MUTATION, VERIFICATION, ROLLBACK }

/** A privileged transaction failure with enough state for callers to decide whether retrying is safe. */
class HostApplyFailure(
    val phase: HostApplyPhase,
    val mutationStarted: Boolean,
    val rollbackComplete: Boolean,
    val indeterminate: Boolean = false,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class HostDispatchFailure(
    val indeterminate: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Host-side telemetry support. Frame telemetry is required to begin an automatic session. */
data class HostAutoCapabilities(
    val frameStats: Boolean,
    val cpuLoad: Boolean,
    val cpuClocks: Boolean,
    val gpuBusy: Boolean,
    val gpuClock: Boolean,
    val thermal: Boolean,
    val frameBackend: String? = null,
    val unsupportedReason: String? = null,
) {
    val autoSessionSupported: Boolean get() = frameStats
}

data class AutoSessionRequest(
    val packageName: String,
    val targetFps: Int,
    val heartbeatTimeoutMs: Long = 15_000L,
)

data class HostAutoSessionHandle(val sessionId: String, val hostEpoch: Long)

enum class HostAutoSessionStatus {
    ACTIVE,
    STOPPED,
    EXPIRED,
    STALE,
    UNSUPPORTED,
    RESTORE_FAILED,
}

data class HostThermalReading(
    val type: String,
    val temperatureMilliCelsius: Long,
)

/** One bounded telemetry sample. Nullable metrics are explicitly unavailable on this device. */
data class HostAutoTelemetry(
    val sequence: Long,
    val timestampNanos: Long,
    val frameBackend: String?,
    val frameConfidencePermille: Int,
    val frameLayer: String?,
    val frameCount: Int,
    val fpsMilli: Int?,
    val frameTimeP95Nanos: Long?,
    val slowFrameRatioPermille: Int?,
    val frameStale: Boolean,
    val cpuLoadPermille: List<Int?>,
    val cpuClockKHz: List<Long?>,
    val gpuBusyPermille: Int?,
    val gpuClockHz: Long?,
    val thermal: List<HostThermalReading>,
    val unsupportedMetrics: List<String> = emptyList(),
)

/** Returned by every session command so stale IDs and unsupported devices remain typed. */
data class HostAutoSessionSnapshot(
    val sessionId: String?,
    val hostEpoch: Long,
    val status: HostAutoSessionStatus,
    val targetFps: Int,
    val telemetry: HostAutoTelemetry? = null,
    val state: HostState? = null,
    val restorationAttempted: Boolean = false,
    val restorationComplete: Boolean = false,
    val message: String? = null,
) {
    init {
        require(targetFps >= 0 && (status != HostAutoSessionStatus.ACTIVE || targetFps > 0)) {
            "invalid target FPS"
        }
    }

    val handle: HostAutoSessionHandle?
        get() = sessionId?.let { HostAutoSessionHandle(it, hostEpoch) }
}

class RemoteHostSessionFailure(
    val requestCode: Int,
    val indeterminate: Boolean,
    message: String,
) : IllegalStateException(message)
