package com.aure.clustertune.root.host

import java.util.UUID

data class HostCheckpointNode(
    val path: String,
    val value: Long,
    val mode: Int,
    val maximum: Boolean,
    val pairedMaximumPath: String? = null,
)

data class HostHardwareCheckpoint(val nodes: List<HostCheckpointNode>)

data class HostCheckpointRestoreResult(
    val complete: Boolean,
    val failures: List<String> = emptyList(),
)

object HostHardwareStateReader {
    fun read(fs: HostFilesystem, capabilities: HostCapabilities): HostState {
        val cpuMax = capabilities.cpus.map { readLong(fs, it.maxPath) }
        val cpuMin = capabilities.cpus.map { readLong(fs, it.minPath) }
        val cpuCurrent = capabilities.cpus.map { cpu -> cpu.curPath?.let { readLong(fs, it) } ?: -1L }
        val gpu = capabilities.gpu
        return HostState(
            cpuMax = cpuMax,
            cpuMin = cpuMin,
            cpuCurrent = cpuCurrent,
            gpuMax = gpu?.let { readLong(fs, it.maxPath) },
            gpuMin = gpu?.minPath?.let { readLong(fs, it) }?.takeUnless { it == -1L },
            gpuCurrent = gpu?.curPath?.let { readLong(fs, it) }?.takeUnless { it == -1L },
        )
    }

    private fun readLong(fs: HostFilesystem, path: String): Long = fs.read(path)?.toLongOrNull() ?: -1L
}

/** Captures and restores only the domains discovered by the host; callers cannot provide paths. */
class HostCheckpointEngine(
    private val fs: HostFilesystem,
    private val capabilities: HostCapabilities,
) {
    fun capture(): Result<HostHardwareCheckpoint> = runCatching {
        val nodes = mutableListOf<HostCheckpointNode>()
        capabilities.cpus.forEach { cpu ->
            nodes += captureNode(cpu.maxPath, maximum = true)
            nodes += captureNode(cpu.minPath, maximum = false, pairedMaximumPath = cpu.maxPath)
        }
        capabilities.gpu?.let { gpu ->
            nodes += captureNode(gpu.maxPath, maximum = true)
            gpu.minPath?.let { nodes += captureNode(it, maximum = false, pairedMaximumPath = gpu.maxPath) }
        }
        HostHardwareCheckpoint(nodes.distinctBy(HostCheckpointNode::path))
    }

    fun restore(checkpoint: HostHardwareCheckpoint): HostCheckpointRestoreResult {
        val allowedPaths = buildSet {
            capabilities.cpus.forEach { add(it.maxPath); add(it.minPath) }
            capabilities.gpu?.let { gpu -> add(gpu.maxPath); gpu.minPath?.let(::add) }
        }
        val failures = mutableListOf<String>()
        val nodes = checkpoint.nodes.filter { it.path in allowedPaths }
        if (nodes.size != checkpoint.nodes.size || nodes.map { it.path }.toSet().size != checkpoint.nodes.size) {
            return HostCheckpointRestoreResult(false, listOf("checkpoint paths do not match discovered domains"))
        }

        // Restore every ceiling first. A later minimum restore is safe only if its paired
        // ceiling reached the exact checkpoint value.
        val maximumRestored = mutableMapOf<String, Boolean>()
        nodes.filter(HostCheckpointNode::maximum).asReversed().forEach { node ->
            val restored = runCatching { restoreExact(node) }.getOrDefault(false)
            maximumRestored[node.path] = restored
            if (!restored) failures += node.path
        }
        nodes.filterNot(HostCheckpointNode::maximum).asReversed().forEach { node ->
            val ceiling = node.pairedMaximumPath
            val safe = ceiling != null && maximumRestored[ceiling] == true &&
                fs.read(ceiling)?.toLongOrNull()?.let { it >= node.value } == true
            val restored = runCatching { if (safe) restoreExact(node) else restoreModeOnly(node) }.getOrDefault(false)
            if (!restored) failures += node.path
            if (!safe) failures += "${node.path} value restore skipped: unsafe ceiling"
        }
        return HostCheckpointRestoreResult(failures.isEmpty(), failures.distinct().take(32))
    }

    private fun captureNode(path: String, maximum: Boolean, pairedMaximumPath: String? = null): HostCheckpointNode {
        require(fs.exists(path)) { "checkpoint node disappeared: $path" }
        val value = fs.read(path)?.toLongOrNull()?.takeIf { it >= 0L } ?: error("cannot read checkpoint value for $path")
        val mode = fs.mode(path) ?: error("cannot read checkpoint mode for $path")
        return HostCheckpointNode(path, value, mode, maximum, pairedMaximumPath)
    }

    private fun restoreExact(node: HostCheckpointNode): Boolean {
        var ok = fs.chmod(node.path, writableMode(node.mode))
        ok = fs.write(node.path, node.value.toString()) && ok
        ok = (fs.read(node.path)?.toLongOrNull() == node.value) && ok
        ok = fs.chmod(node.path, node.mode) && ok
        ok = (fs.mode(node.path) == node.mode) && ok
        return ok
    }

    private fun restoreModeOnly(node: HostCheckpointNode): Boolean =
        fs.chmod(node.path, node.mode) && fs.mode(node.path) == node.mode

    private fun writableMode(mode: Int): Int = mode or 0x080
}

/** Owns the one automatic tuning session allowed in a privileged host process. */
class HostAutoSessionController(
    private val hostCapabilities: HostCapabilities,
    private val fs: HostFilesystem,
    private val applyEngine: HostApplyEngine,
    private val telemetrySource: HostTelemetrySource,
    private val hostEpoch: Long,
    private val clock: HostMonotonicClock = SystemHostMonotonicClock,
) {
    private data class ActiveSession(
        val id: String,
        val targetFps: Int,
        val timeoutNanos: Long,
        val checkpoint: HostHardwareCheckpoint,
        val cpuFloorEnvelope: List<Long>,
        val cpuCeilingEnvelope: List<Long>,
        val gpuFloorEnvelope: Long?,
        val gpuCeilingEnvelope: Long?,
        var deadlineNanos: Long,
        var sequence: Long = 0L,
        var latestTelemetry: HostAutoTelemetry? = null,
        var lastAppliedRequest: ApplyRequest? = null,
    )

    /**
     * A failed restore remains authoritative until the same checkpoint is restored exactly.
     * In particular, the partially restored hardware must never become a new session baseline.
     */
    private data class PendingRestoration(
        val session: ActiveSession,
        val requestedStatus: HostAutoSessionStatus,
        val message: String,
    )

    private var active: ActiveSession? = null
    private var pendingRestoration: PendingRestoration? = null
    private var terminal: HostAutoSessionSnapshot? = null

    companion object {
        private const val MIN_HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val MAX_HEARTBEAT_TIMEOUT_MS = 120_000L

        @JvmStatic
        fun production(
            capabilities: HostCapabilities,
            fs: HostFilesystem,
            applyEngine: HostApplyEngine,
            hostEpoch: Long,
        ): HostAutoSessionController = HostAutoSessionController(
            hostCapabilities = capabilities,
            fs = fs,
            applyEngine = applyEngine,
            telemetrySource = SystemHostTelemetrySource(fs, capabilities),
            hostEpoch = hostEpoch,
        )
    }

    @Synchronized
    fun capabilities(): HostAutoCapabilities = telemetrySource.capabilities()

    @Synchronized
    fun start(request: AutoSessionRequest): HostAutoSessionSnapshot {
        expireLocked()
        require(HostTelemetryParsers.isValidPackageName(request.packageName)) { "invalid target package" }
        require(request.targetFps in 15..240) { "target FPS must be between 15 and 240" }
        require(request.heartbeatTimeoutMs in MIN_HEARTBEAT_TIMEOUT_MS..MAX_HEARTBEAT_TIMEOUT_MS) {
            "heartbeat timeout must be between $MIN_HEARTBEAT_TIMEOUT_MS and $MAX_HEARTBEAT_TIMEOUT_MS ms"
        }
        pendingRestoration?.let { pending ->
            val restored = restorePendingLocked(
                pending,
                HostAutoSessionStatus.STOPPED,
                "automatic session restored before replacement",
            )
            if (!restored.restorationComplete) return restored
        }
        active?.let {
            val stopped = stopLocked(it, HostAutoSessionStatus.STOPPED, "replaced by a new automatic session")
            if (!stopped.restorationComplete) return stopped
        }
        val support = telemetrySource.capabilities()
        if (!support.autoSessionSupported) {
            return unsupported(support.unsupportedReason ?: "automatic telemetry is unavailable")
        }
        val checkpoint = HostCheckpointEngine(fs, hostCapabilities).capture().getOrElse { failure ->
            return unsupported("unable to checkpoint tuning state: ${failure.message}")
        }
        val telemetryStart = telemetrySource.begin(request.packageName, request.targetFps)
        if (telemetryStart.isFailure) {
            telemetrySource.end()
            return unsupported(telemetryStart.exceptionOrNull()?.message ?: "frame telemetry is unavailable")
        }
        val now = clock.nanoTime()
        val timeoutNanos = request.heartbeatTimeoutMs * 1_000_000L
        val session = ActiveSession(
            id = UUID.randomUUID().toString(),
            targetFps = request.targetFps,
            timeoutNanos = timeoutNanos,
            checkpoint = checkpoint,
            cpuFloorEnvelope = hostCapabilities.cpus.map { cpu -> checkpoint.valueFor(cpu.minPath) },
            cpuCeilingEnvelope = hostCapabilities.cpus.map { cpu -> checkpoint.valueFor(cpu.maxPath) },
            gpuFloorEnvelope = hostCapabilities.gpu?.minPath?.let { path -> checkpoint.valueFor(path) },
            gpuCeilingEnvelope = hostCapabilities.gpu?.let { checkpoint.valueFor(it.maxPath) },
            deadlineNanos = deadline(now, timeoutNanos),
        )
        active = session
        terminal = null
        return activeSnapshot(session, state = HostHardwareStateReader.read(fs, hostCapabilities))
    }

    /** A null ID means the currently active owner session. */
    @Synchronized
    fun readTelemetry(sessionId: String?, expectedHostEpoch: Long?, afterSequence: Long = -1L): HostAutoSessionSnapshot {
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch) ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        if (session.latestTelemetry != null && afterSequence >= 0L && afterSequence < session.sequence) {
            return activeSnapshot(session, telemetry = session.latestTelemetry)
        }
        val raw = runCatching(telemetrySource::sample).getOrElse { failure ->
            HostRawTelemetry(
                timestampNanos = clock.nanoTime(),
                frameBackend = telemetrySource.capabilities().frameBackend,
                frameConfidencePermille = 0,
                frameLayer = null,
                frameCount = 0,
                fpsMilli = null,
                frameTimeP95Nanos = null,
                slowFrameRatioPermille = null,
                frameStale = true,
                cpuLoadPermille = List(hostCapabilities.cpus.size) { null },
                cpuClockKHz = List(hostCapabilities.cpus.size) { null },
                gpuBusyPermille = null,
                gpuClockHz = null,
                thermal = emptyList(),
                unsupportedMetrics = listOf("telemetry_error:${failure.message.orEmpty().take(128)}"),
            )
        }
        session.sequence++
        val telemetry = HostAutoTelemetry(
            sequence = session.sequence,
            timestampNanos = raw.timestampNanos,
            frameBackend = raw.frameBackend?.take(HostProtocol.MAX_METADATA_LENGTH),
            frameConfidencePermille = raw.frameConfidencePermille.coerceIn(0, 1000),
            frameLayer = raw.frameLayer?.take(HostProtocol.MAX_METADATA_LENGTH),
            frameCount = raw.frameCount.coerceIn(0, 100_000),
            fpsMilli = raw.fpsMilli?.coerceIn(0, 1_000_000),
            frameTimeP95Nanos = raw.frameTimeP95Nanos?.takeIf { it >= 0L },
            slowFrameRatioPermille = raw.slowFrameRatioPermille?.coerceIn(0, 1000),
            frameStale = raw.frameStale,
            cpuLoadPermille = boundedNullableInts(raw.cpuLoadPermille, hostCapabilities.cpus.size),
            cpuClockKHz = boundedNullableLongs(raw.cpuClockKHz, hostCapabilities.cpus.size),
            gpuBusyPermille = raw.gpuBusyPermille?.coerceIn(0, 1000),
            gpuClockHz = raw.gpuClockHz?.takeIf { it >= 0L },
            thermal = raw.thermal.take(HostProtocol.MAX_THERMAL_READINGS).map {
                it.copy(type = it.type.take(64))
            },
            unsupportedMetrics = raw.unsupportedMetrics.map { it.take(HostProtocol.MAX_METADATA_LENGTH) }
                .distinct().take(HostProtocol.MAX_UNSUPPORTED_METRICS),
        )
        session.latestTelemetry = telemetry
        return activeSnapshot(session, telemetry = telemetry)
    }

    @Synchronized
    fun applyStep(sessionId: String, expectedHostEpoch: Long, request: ApplyRequest): HostAutoSessionSnapshot {
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch) ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        validateRequestIdentity(request)
        validateWithinEnvelope(session, request)
        touch(session)
        val current = HostHardwareStateReader.read(fs, hostCapabilities)
        // The kernel or another privileged actor can change a sysfs value between steps.
        // A cached request is therefore only bookkeeping; live hardware is authoritative.
        val alreadyApplied = requestMatchesState(request, current)
        if (!alreadyApplied) {
            applyEngine.applyOrThrow(capabilitiesWithinEnvelope(session), request)
            session.lastAppliedRequest = request
        }
        return activeSnapshot(session, state = HostHardwareStateReader.read(fs, hostCapabilities))
    }

    @Synchronized
    fun heartbeat(sessionId: String, expectedHostEpoch: Long): HostAutoSessionSnapshot {
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch) ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        touch(session)
        return activeSnapshot(session, telemetry = session.latestTelemetry)
    }

    /** A null ID deliberately preempts whichever owner session is current. */
    @Synchronized
    fun stop(sessionId: String?, expectedHostEpoch: Long?): HostAutoSessionSnapshot {
        expireLocked()
        resolveActive(sessionId, expectedHostEpoch)?.let { session ->
            return stopLocked(session, HostAutoSessionStatus.STOPPED, "automatic session stopped")
        }
        resolvePendingRestoration(sessionId, expectedHostEpoch)?.let { pending ->
            return restorePendingLocked(pending, HostAutoSessionStatus.STOPPED, "automatic session stopped")
        }
        return staleOrTerminal(sessionId, expectedHostEpoch)
    }

    /** Called before a normal profile apply, host replacement, or host shutdown. */
    @Synchronized
    fun stopCurrent(reason: String): HostAutoSessionSnapshot? {
        expireLocked()
        pendingRestoration?.let { pending ->
            return restorePendingLocked(pending, HostAutoSessionStatus.STOPPED, reason)
        }
        val session = active ?: return null
        return stopLocked(session, HostAutoSessionStatus.STOPPED, reason)
    }

    @Synchronized
    fun expireIfNeeded(): HostAutoSessionSnapshot? {
        pendingRestoration?.let { pending ->
            return restorePendingLocked(pending, pending.requestedStatus, pending.message)
        }
        expireLocked()
        return terminal
    }

    @Synchronized
    fun current(): HostAutoSessionSnapshot = active?.let { activeSnapshot(it, telemetry = it.latestTelemetry) }
        ?: terminal
        ?: noSessionSnapshot()

    private fun expireLocked() {
        val session = active ?: return
        if (clock.nanoTime() >= session.deadlineNanos) {
            stopLocked(session, HostAutoSessionStatus.EXPIRED, "automatic session heartbeat expired")
        }
    }

    private fun stopLocked(
        session: ActiveSession,
        requestedStatus: HostAutoSessionStatus,
        message: String,
    ): HostAutoSessionSnapshot {
        if (active !== session) return staleOrTerminal(session.id, hostEpoch)
        active = null
        runCatching(telemetrySource::end)
        val pending = PendingRestoration(session, requestedStatus, message)
        pendingRestoration = pending
        return restorePendingLocked(pending, requestedStatus, message)
    }

    private fun restorePendingLocked(
        pending: PendingRestoration,
        requestedStatus: HostAutoSessionStatus,
        message: String,
    ): HostAutoSessionSnapshot {
        if (pendingRestoration !== pending) return staleOrTerminal(pending.session.id, hostEpoch)
        val session = pending.session
        val restoration = runCatching { HostCheckpointEngine(fs, hostCapabilities).restore(session.checkpoint) }
            .getOrElse { failure ->
                HostCheckpointRestoreResult(false, listOf("restore error: ${failure.message.orEmpty().take(128)}"))
            }
        if (restoration.complete) pendingRestoration = null
        val status = if (restoration.complete) requestedStatus else HostAutoSessionStatus.RESTORE_FAILED
        val detail = if (restoration.complete) message else "$message; restore incomplete for ${restoration.failures.joinToString()}"
        return HostAutoSessionSnapshot(
            sessionId = session.id,
            hostEpoch = hostEpoch,
            status = status,
            targetFps = session.targetFps,
            telemetry = session.latestTelemetry,
            state = HostHardwareStateReader.read(fs, hostCapabilities),
            restorationAttempted = true,
            restorationComplete = restoration.complete,
            message = detail.take(HostProtocol.MAX_METADATA_LENGTH),
        ).also { terminal = it }
    }

    private fun validateRequestIdentity(request: ApplyRequest) {
        require(request.cpuMax.size == hostCapabilities.cpus.size) { "CPU domain count mismatch" }
        if (request.cpuIds.isNotEmpty()) {
            require(request.cpuIds == hostCapabilities.cpus.map(CpuDomain::id)) { "CPU domain order mismatch" }
        }
        request.gpuId?.let { require(it == hostCapabilities.gpu?.id) { "GPU identity mismatch" } }
        request.gpuMaxPath?.let { require(it == hostCapabilities.gpu?.maxPath) { "GPU path mismatch" } }
    }

    private fun validateWithinEnvelope(session: ActiveSession, request: ApplyRequest) {
        require(!request.resetToStock) { "automatic steps cannot request a stock reset" }
        require(request.stabilizedStockCeiling == null) { "automatic steps cannot override the GPU stock ceiling" }
        request.cpuMax.forEachIndexed { index, requested ->
            val cpu = hostCapabilities.cpus[index]
            val floor = session.cpuFloorEnvelope[index]
            val envelope = session.cpuCeilingEnvelope[index]
            require(
                requested > 0L && requested >= floor && requested <= envelope &&
                    (cpu.supportedFrequencies.isEmpty() || requested == envelope || requested in cpu.supportedFrequencies),
            ) {
                "automatic CPU target is outside the session envelope for ${cpu.id}"
            }
        }
        request.gpuMax?.let { requested ->
            val envelope = session.gpuCeilingEnvelope
                ?: throw IllegalArgumentException("automatic GPU target requested without a checkpointed GPU")
            val gpu = hostCapabilities.gpu
                ?: throw IllegalArgumentException("automatic GPU target requested without a GPU domain")
            val floor = session.gpuFloorEnvelope
            require(
                requested > 0L && (floor == null || requested >= floor) && requested <= envelope &&
                    (gpu.supportedFrequencies.isEmpty() || requested == envelope || requested in gpu.supportedFrequencies),
            ) { "automatic GPU target is outside the session envelope" }
        }
    }

    /** Treat the captured session ceiling as Stock only for this bounded transaction. */
    private fun capabilitiesWithinEnvelope(session: ActiveSession): HostCapabilities = HostCapabilities(
        cpus = hostCapabilities.cpus.mapIndexed { index, cpu ->
            val ceiling = session.cpuCeilingEnvelope[index]
            cpu.copy(
                stockMax = ceiling,
                selectableMax = ceiling,
                currentMax = ceiling,
            )
        },
        gpu = hostCapabilities.gpu?.let { gpu ->
            val ceiling = session.gpuCeilingEnvelope
                ?: throw IllegalStateException("Auto Tune GPU checkpoint is unavailable")
            gpu.copy(
                stockMax = ceiling,
                selectableMax = ceiling,
                currentMax = ceiling,
            )
        },
    )

    private fun requestMatchesState(request: ApplyRequest, state: HostState): Boolean {
        if (request.resetToStock || request.cpuMax != state.cpuMax) return false
        return request.gpuMax == null || request.gpuMax == state.gpuMax
    }

    private fun resolveActive(sessionId: String?, expectedHostEpoch: Long?): ActiveSession? {
        if (expectedHostEpoch != null && expectedHostEpoch != hostEpoch) return null
        val session = active ?: return null
        return session.takeIf { sessionId == null || sessionId == it.id }
    }

    private fun resolvePendingRestoration(sessionId: String?, expectedHostEpoch: Long?): PendingRestoration? {
        if (expectedHostEpoch != null && expectedHostEpoch != hostEpoch) return null
        val pending = pendingRestoration ?: return null
        return pending.takeIf { sessionId == null || sessionId == it.session.id }
    }

    private fun staleOrTerminal(sessionId: String?, expectedHostEpoch: Long?): HostAutoSessionSnapshot {
        terminal?.takeIf {
            (expectedHostEpoch == null || expectedHostEpoch == hostEpoch) &&
                (sessionId == null || sessionId == it.sessionId)
        }?.let { return it }
        if (sessionId == null && expectedHostEpoch == null && active == null) return noSessionSnapshot()
        return HostAutoSessionSnapshot(
            sessionId = sessionId,
            hostEpoch = hostEpoch,
            status = HostAutoSessionStatus.STALE,
            targetFps = 0,
            restorationComplete = active == null && pendingRestoration == null,
            message = when {
                expectedHostEpoch != null && expectedHostEpoch != hostEpoch -> "stale privileged host epoch"
                active == null -> "automatic session is no longer active"
                else -> "stale automatic session ID"
            },
        )
    }

    private fun noSessionSnapshot() = HostAutoSessionSnapshot(
        sessionId = null,
        hostEpoch = hostEpoch,
        status = HostAutoSessionStatus.STOPPED,
        targetFps = 0,
        restorationComplete = true,
        message = "no automatic session",
    )

    private fun unsupported(message: String) = HostAutoSessionSnapshot(
        sessionId = null,
        hostEpoch = hostEpoch,
        status = HostAutoSessionStatus.UNSUPPORTED,
        targetFps = 0,
        restorationComplete = true,
        message = message.take(HostProtocol.MAX_METADATA_LENGTH),
    )

    private fun activeSnapshot(
        session: ActiveSession,
        telemetry: HostAutoTelemetry? = null,
        state: HostState? = null,
    ) = HostAutoSessionSnapshot(
        sessionId = session.id,
        hostEpoch = hostEpoch,
        status = HostAutoSessionStatus.ACTIVE,
        targetFps = session.targetFps,
        telemetry = telemetry,
        state = state,
    )

    private fun touch(session: ActiveSession) {
        session.deadlineNanos = deadline(clock.nanoTime(), session.timeoutNanos)
    }

    private fun deadline(now: Long, timeoutNanos: Long): Long =
        if (now > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else now + timeoutNanos

    private fun boundedNullableInts(values: List<Int?>, expected: Int): List<Int?> =
        List(expected.coerceIn(0, 64)) { index -> values.getOrNull(index)?.coerceIn(0, 1000) }

    private fun boundedNullableLongs(values: List<Long?>, expected: Int): List<Long?> =
        List(expected.coerceIn(0, 64)) { index -> values.getOrNull(index)?.takeIf { it >= 0L } }

    private fun HostHardwareCheckpoint.valueFor(path: String): Long =
        nodes.firstOrNull { it.path == path }?.value ?: error("checkpoint is missing $path")
}
