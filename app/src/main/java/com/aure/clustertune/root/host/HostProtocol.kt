package com.aure.clustertune.root.host

/** Private wire contract between ClusterTune and its persistent privileged host. */
object HostProtocol {
    const val DESCRIPTOR = "com.aure.clustertune.root.host.IClusterTuneHost"
    const val VERSION = 6
    const val SERVICE_PREFIX = "clustertune.host."
    const val PING = 1
    const val HOST_IDENTITY = 2
    const val READ_CAPABILITIES = 3
    const val READ_STATE = 4
    const val APPLY_PROFILE = 5
    const val STOP = 7
    const val READ_SNAPSHOT = 9
    const val LEASE = 10
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
