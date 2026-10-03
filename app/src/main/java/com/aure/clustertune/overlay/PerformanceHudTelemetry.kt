package com.aure.clustertune.overlay

import com.aure.clustertune.root.host.ClusterTuneHostClient
import com.aure.clustertune.root.host.HostAutoTelemetry
import com.aure.clustertune.root.host.HostTelemetrySessionHandle
import com.aure.clustertune.root.host.HostTelemetrySessionSnapshot
import com.aure.clustertune.root.host.HostTelemetrySessionStatus
import com.aure.clustertune.root.host.TelemetrySessionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PerformanceHudTelemetryTarget(
    val packageName: String?,
    val targetFps: Int,
) {
    init {
        require(packageName != null || targetFps == 0)
        require(packageName == null || targetFps > 0)
    }
}

internal data class PerformanceHudTelemetryState(
    val sample: HostAutoTelemetry? = null,
    /** Exact read-only host session that produced [sample]. */
    val streamId: String? = null,
    val message: String? = null,
)

internal interface PerformanceHudTelemetryBackend {
    suspend fun start(request: TelemetrySessionRequest): HostTelemetrySessionSnapshot
    suspend fun read(handle: HostTelemetrySessionHandle, afterSequence: Long): HostTelemetrySessionSnapshot
    suspend fun stop(handle: HostTelemetrySessionHandle): HostTelemetrySessionSnapshot
}

internal class ClusterTunePerformanceHudTelemetryBackend(
    private val client: ClusterTuneHostClient,
) : PerformanceHudTelemetryBackend {
    override suspend fun start(request: TelemetrySessionRequest): HostTelemetrySessionSnapshot =
        withContext(Dispatchers.IO) { client.startTelemetrySession(request).getOrThrow() }

    override suspend fun read(
        handle: HostTelemetrySessionHandle,
        afterSequence: Long,
    ): HostTelemetrySessionSnapshot = withContext(Dispatchers.IO) {
        client.readTelemetrySession(handle, afterSequence).getOrThrow()
    }

    override suspend fun stop(handle: HostTelemetrySessionHandle): HostTelemetrySessionSnapshot =
        withContext(Dispatchers.IO) { client.stopTelemetrySession(handle).getOrThrow() }
}

/** Owns exactly one read-only host handle and always closes it on cancellation or replacement. */
internal class PerformanceHudTelemetryRunner(
    private val backend: PerformanceHudTelemetryBackend,
    private val sampleIntervalMillis: Long = 1_000L,
) {
    init {
        require(sampleIntervalMillis in 100L..10_000L)
    }

    suspend fun run(
        target: PerformanceHudTelemetryTarget,
        onState: (PerformanceHudTelemetryState) -> Unit,
    ) {
        var handle: HostTelemetrySessionHandle? = null
        var lastSequence = -1L
        try {
            onState(PerformanceHudTelemetryState(message = "Starting telemetry"))
            val started = backend.start(
                TelemetrySessionRequest(
                    packageName = target.packageName,
                    targetFps = target.targetFps,
                ),
            )
            if (started.status != HostTelemetrySessionStatus.ACTIVE) {
                onState(PerformanceHudTelemetryState(message = started.message ?: "Telemetry unavailable"))
                return
            }
            handle = started.handle ?: error("Active telemetry session returned no handle")
            started.telemetry?.let { sample ->
                lastSequence = sample.sequence
                onState(PerformanceHudTelemetryState(sample = sample, streamId = handle.sessionId))
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val snapshot = backend.read(handle, lastSequence)
                if (snapshot.status != HostTelemetrySessionStatus.ACTIVE) {
                    onState(PerformanceHudTelemetryState(message = snapshot.message ?: "Telemetry stopped"))
                    return
                }
                val sample = snapshot.telemetry
                    ?: error("Active telemetry session returned no sample")
                check(sample.sequence > lastSequence) { "Telemetry sample did not advance" }
                lastSequence = sample.sequence
                onState(PerformanceHudTelemetryState(sample = sample, streamId = handle.sessionId))
                delay(sampleIntervalMillis)
            }
        } finally {
            handle?.let { activeHandle ->
                withContext(NonCancellable) {
                    runCatching { backend.stop(activeHandle) }
                }
            }
        }
    }
}
