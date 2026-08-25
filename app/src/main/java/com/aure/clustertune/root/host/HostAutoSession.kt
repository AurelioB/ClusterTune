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

/** Frozen ownership evidence used while an automatic-session checkpoint is restored. */
internal data class HostCeilingRestorePlan(
    val ownedValues: MutableMap<String, Long>,
    val unresolvedCandidates: MutableMap<String, Set<Long>>,
    /** Ceiling permission modes that Auto Tune may have changed and still owns. */
    val ownedModePaths: MutableSet<String>,
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
    fun capture(): Result<HostHardwareCheckpoint> = capture(includeMinimums = true)

    /** Auto Tune owns only ceiling nodes, so its checkpoint must not restore minimum votes. */
    fun captureCeilings(): Result<HostHardwareCheckpoint> = capture(includeMinimums = false)

    private fun capture(includeMinimums: Boolean): Result<HostHardwareCheckpoint> = runCatching {
        val nodes = mutableListOf<HostCheckpointNode>()
        capabilities.cpus.forEach { cpu ->
            nodes += captureNode(cpu.maxPath, maximum = true)
            if (includeMinimums) {
                nodes += captureNode(cpu.minPath, maximum = false, pairedMaximumPath = cpu.maxPath)
            }
        }
        capabilities.gpu?.let { gpu ->
            nodes += captureNode(gpu.maxPath, maximum = true)
            if (includeMinimums) {
                gpu.minPath?.let { nodes += captureNode(it, maximum = false, pairedMaximumPath = gpu.maxPath) }
            }
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

    /**
     * Restores only ceilings that Auto Tune still owns. The ownership plan is captured when
     * stopping begins and is deliberately supplied by the caller so a retry cannot claim a
     * ceiling that belonged to another privileged actor at the time of preemption.
     *
     * Each retry also checks the expected owned value immediately before writing. This avoids
     * overwriting an external change made after the stop was requested. A value that already
     * equals the checkpoint is considered restored and only has its original mode reapplied.
     */
    internal fun restoreOwnedCeilings(
        checkpoint: HostHardwareCheckpoint,
        plan: HostCeilingRestorePlan,
    ): HostCheckpointRestoreResult {
        val allowedMaximumPaths = buildSet {
            capabilities.cpus.forEach { add(it.maxPath) }
            capabilities.gpu?.let { add(it.maxPath) }
        }
        val checkpointPaths = checkpoint.nodes.map(HostCheckpointNode::path)
        if (checkpoint.nodes.any { !it.maximum || it.path !in allowedMaximumPaths } ||
            checkpointPaths.toSet().size != checkpointPaths.size ||
            plan.ownedValues.keys.any { it !in checkpointPaths } ||
            plan.unresolvedCandidates.keys.any { it !in checkpointPaths } ||
            plan.ownedModePaths.any { it !in checkpointPaths } ||
            plan.ownedValues.keys.any { it in plan.unresolvedCandidates } ||
            plan.unresolvedCandidates.values.any { candidates -> candidates.isEmpty() || candidates.any { it <= 0L } }
        ) {
            return HostCheckpointRestoreResult(false, listOf("checkpoint paths do not match discovered ceiling domains"))
        }

        val failures = mutableListOf<String>()
        checkpoint.nodes.asReversed().forEach { node ->
            val expectedOwned = plan.ownedValues[node.path]
            val unresolvedCandidates = plan.unresolvedCandidates[node.path]
            val ownsMode = node.path in plan.ownedModePaths
            if (expectedOwned == null && unresolvedCandidates == null && !ownsMode) {
                return@forEach
            }
            val current = fs.read(node.path)?.toLongOrNull()?.takeIf { it >= 0L }
            val restored = runCatching {
                when {
                    current == null -> {
                        val modeRestored = !ownsMode || restoreOwnedModeOnly(node)
                        if (expectedOwned != null || unresolvedCandidates != null) false else modeRestored
                    }
                    expectedOwned != null && current == node.value -> {
                        // The ceiling is already at its checkpoint. Relinquish value ownership
                        // even if restoring its permission mode fails; a later retry must only
                        // retry the mode and never reclaim a value an external writer selected.
                        plan.ownedValues.remove(node.path)
                        !ownsMode || restoreOwnedModeOnly(node)
                    }
                    expectedOwned != null && current == expectedOwned -> {
                        val restoredValue = restoreOwnedValueAndMode(node, restoreCheckpointMode = ownsMode)
                        // A write can reach the checkpoint while a subsequent mode/readback
                        // operation fails. Reconcile before deciding whether to retain ownership.
                        if (fs.read(node.path)?.toLongOrNull() == node.value) {
                            plan.ownedValues.remove(node.path)
                        }
                        restoredValue
                    }
                    expectedOwned != null -> {
                        // Another writer changed this node after the stop plan was captured.
                        // Resolve it as external permanently so a retry cannot later reclaim it.
                        plan.ownedValues.remove(node.path)
                        !ownsMode || restoreOwnedModeOnly(node)
                    }
                    unresolvedCandidates != null -> {
                        plan.unresolvedCandidates.remove(node.path)
                        when {
                            current == node.value -> !ownsMode || restoreOwnedModeOnly(node)
                            current in unresolvedCandidates -> {
                                plan.ownedValues[node.path] = current
                                val restoredValue = restoreOwnedValueAndMode(node, restoreCheckpointMode = ownsMode)
                                if (fs.read(node.path)?.toLongOrNull() == node.value) {
                                    plan.ownedValues.remove(node.path)
                                }
                                restoredValue
                            }
                            else -> !ownsMode || restoreOwnedModeOnly(node)
                        }
                    }
                    ownsMode -> restoreOwnedModeOnly(node)
                    else -> true
                }
            }.getOrDefault(false)
            if (restored) plan.ownedModePaths.remove(node.path)
            if (!restored) {
                failures += if (current == null && (expectedOwned != null || unresolvedCandidates != null)) {
                    "${node.path} (ownership unreadable)"
                } else {
                    node.path
                }
            }
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

    /**
     * Auto Tune may leave a maximum at the checkpoint, writable, or protected mode. Restore
     * only those known modes; a different mode is treated as an external actor's state.
     */
    private fun restoreOwnedModeOnly(node: HostCheckpointNode): Boolean {
        val currentMode = fs.mode(node.path) ?: return false
        if (currentMode !in autoOwnedModes(node)) return true
        return currentMode == node.mode || (fs.chmod(node.path, node.mode) && fs.mode(node.path) == node.mode)
    }

    /** Restore an Auto-owned value while preserving a newer external permission mode. */
    private fun restoreOwnedValueAndMode(
        node: HostCheckpointNode,
        restoreCheckpointMode: Boolean,
    ): Boolean {
        val currentMode = fs.mode(node.path) ?: return false
        val finalMode = if (restoreCheckpointMode && currentMode in autoOwnedModes(node)) node.mode else currentMode
        var ok = fs.chmod(node.path, writableMode(finalMode))
        ok = fs.write(node.path, node.value.toString()) && ok
        ok = (fs.read(node.path)?.toLongOrNull() == node.value) && ok
        ok = fs.chmod(node.path, finalMode) && ok
        ok = (fs.mode(node.path) == finalMode) && ok
        return ok
    }

    private fun autoOwnedModes(node: HostCheckpointNode): Set<Int> = setOf(
        node.mode,
        writableMode(node.mode),
        node.mode and 0x16d,
        writableMode(node.mode and 0x16d),
    )

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
        val cpuLowerEnvelope: List<Long>,
        val cpuCeilingEnvelope: List<Long>,
        val gpuLowerEnvelope: Long?,
        val gpuCeilingEnvelope: Long?,
        var deadlineNanos: Long,
        var sequence: Long = 0L,
        var latestTelemetry: HostAutoTelemetry? = null,
        var lastOwnedCpuMax: List<Long>,
        var lastOwnedGpuMax: Long?,
        val lastOwnedMaxModes: MutableMap<String, Int>,
        val ownedModePaths: MutableSet<String>,
    )

    /**
     * A failed restore remains authoritative until its ownership-aware restoration plan
     * completes. In particular, partially restored hardware cannot become a new baseline.
     */
    private data class PendingRestoration(
        val session: ActiveSession,
        val requestedStatus: HostAutoSessionStatus,
        val message: String,
        val restorePlan: HostCeilingRestorePlan,
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
        require(request.targetFps > 0) { "target FPS must be positive" }
        require(request.heartbeatTimeoutMs in MIN_HEARTBEAT_TIMEOUT_MS..MAX_HEARTBEAT_TIMEOUT_MS) {
            "heartbeat timeout must be between $MIN_HEARTBEAT_TIMEOUT_MS and $MAX_HEARTBEAT_TIMEOUT_MS ms"
        }
        request.baseline?.let(::validateBaselineRequest)
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
        val preBaselineCheckpoint = HostCheckpointEngine(fs, hostCapabilities).captureCeilings().getOrElse { failure ->
            return unsupported("unable to checkpoint tuning state: ${failure.message}")
        }
        val preBaselineState = HostHardwareStateReader.read(fs, hostCapabilities)
        if (!checkpointMatchesState(preBaselineCheckpoint, preBaselineState)) {
            return unsupported("frequency ceilings changed while Auto Tune was starting")
        }
        val telemetryStart = telemetrySource.begin(request.packageName, request.targetFps)
        if (telemetryStart.isFailure) {
            runCatching(telemetrySource::end)
            return unsupported(telemetryStart.exceptionOrNull()?.message ?: "frame telemetry is unavailable")
        }
        val stateAfterTelemetryStart = HostHardwareStateReader.read(fs, hostCapabilities)
        if (!checkpointMatchesState(preBaselineCheckpoint, stateAfterTelemetryStart)) {
            runCatching(telemetrySource::end)
            return unsupported("frequency ceilings changed while Auto Tune telemetry was starting")
        }
        val now = clock.nanoTime()
        val sessionId = UUID.randomUUID().toString()
        val provisional = runCatching {
            createSession(
                request = request,
                id = sessionId,
                checkpoint = preBaselineCheckpoint,
                state = stateAfterTelemetryStart,
                now = now,
            )
        }.getOrElse { failure ->
            runCatching(telemetrySource::end)
            return unsupported("unable to create Auto Tune session: ${failure.message}")
        }
        active = provisional
        terminal = null

        val baseline = request.baseline
        if (baseline == null) {
            return activeSnapshot(provisional, state = stateAfterTelemetryStart)
        }

        val resolved = resolveHostMaximumTargets(hostCapabilities, baseline)
        var baselineApplySucceeded = false
        var confirmedBaselineState: HostState? = null
        return try {
            applyEngine.applyMaxOnlyOrThrow(hostCapabilities, baseline)
            baselineApplySucceeded = true

            val baselineState = HostHardwareStateReader.read(fs, hostCapabilities)
            requireBaselineStateMatches(
                before = preBaselineState,
                after = baselineState,
                request = baseline,
                resolved = resolved,
            )
            confirmedBaselineState = baselineState
            // Until the final checkpoint is captured, the provisional owner must recognize
            // every value the successful profile transaction was allowed to leave behind.
            provisional.lastOwnedCpuMax = baselineState.cpuMax
            provisional.lastOwnedGpuMax = baselineState.gpuMax

            val baselineCheckpoint = HostCheckpointEngine(fs, hostCapabilities).captureCeilings().getOrThrow()
            val verifiedState = HostHardwareStateReader.read(fs, hostCapabilities)
            check(checkpointMatchesState(baselineCheckpoint, verifiedState)) {
                "frequency ceilings changed while the Auto Tune baseline was checkpointed"
            }
            requireBaselineStateMatches(
                before = preBaselineState,
                after = verifiedState,
                request = baseline,
                resolved = resolved,
            )
            val session = createSession(
                request = request,
                id = sessionId,
                checkpoint = baselineCheckpoint,
                state = verifiedState,
                now = now,
            )
            active = session
            activeSnapshot(session, state = verifiedState)
        } catch (failure: Throwable) {
            val applyFailure = failure as? HostApplyFailure
            val requestedValuesMayRemain = baselineApplySucceeded || applyFailure?.let {
                it.mutationStarted && (it.indeterminate || !it.rollbackComplete)
            } == true
            stopLocked(
                session = provisional,
                requestedStatus = HostAutoSessionStatus.STOPPED,
                message = "automatic session baseline failed: ${failure.message.orEmpty().take(128)}",
                observedState = HostHardwareStateReader.read(fs, hostCapabilities),
                ownershipCandidates = baselineOwnershipCandidates(
                    before = preBaselineState,
                    request = baseline,
                    resolved = resolved,
                    confirmedState = confirmedBaselineState,
                    includeAcceptedCandidates = requestedValuesMayRemain && confirmedBaselineState == null,
                    rollbackOwnedValues = applyFailure?.rollbackOwnedValues.orEmpty(),
                ),
                modeOwnershipCandidates = if (requestedValuesMayRemain) requestMaximumPaths(baseline) else emptySet(),
            )
        }
    }

    /** A null ID means the currently active owner session. */
    @Synchronized
    fun readTelemetry(sessionId: String?, expectedHostEpoch: Long?, afterSequence: Long = -1L): HostAutoSessionSnapshot {
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch) ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        val current = HostHardwareStateReader.read(fs, hostCapabilities)
        externalCeilingDrift(session, current)?.let { detail ->
            return stopLocked(
                session,
                HostAutoSessionStatus.STOPPED,
                "automatic session stopped: external maximum changed ($detail)",
                observedState = current,
            )
        }
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
        externalCeilingDrift(session, current)?.let { detail ->
            return stopLocked(
                session,
                HostAutoSessionStatus.STOPPED,
                "automatic session stopped: external maximum changed ($detail)",
                observedState = current,
            )
        }
        val alreadyApplied = requestMatchesState(request, current)
        val appliedState = if (!alreadyApplied) {
            val previousCpuMax = session.lastOwnedCpuMax
            val previousGpuMax = session.lastOwnedGpuMax
            val sessionCapabilities = capabilitiesWithinEnvelope(session)
            val resolvedStep = resolveHostMaximumTargets(sessionCapabilities, request)
            try {
                applyEngine.applyMaxOnlyOrThrow(sessionCapabilities, request)
            } catch (failure: Throwable) {
                val applyFailure = failure as? HostApplyFailure
                val requestedValuesMayRemain = applyFailure?.let {
                    it.mutationStarted && (it.indeterminate || !it.rollbackComplete)
                } == true
                val ownershipCandidates = buildMap<String, Set<Long>> {
                    hostCapabilities.cpus.forEachIndexed { index, cpu ->
                        put(
                            cpu.maxPath,
                            buildSet {
                                add(previousCpuMax[index])
                                if (requestedValuesMayRemain) add(request.cpuMax[index])
                                addAll(applyFailure?.rollbackOwnedValues?.get(cpu.maxPath).orEmpty())
                            },
                        )
                    }
                    hostCapabilities.gpu?.let { gpu ->
                        put(
                            gpu.maxPath,
                            buildSet {
                                previousGpuMax?.let(::add)
                                if (requestedValuesMayRemain) request.gpuMax?.let(::add)
                                addAll(applyFailure?.rollbackOwnedValues?.get(gpu.maxPath).orEmpty())
                            },
                        )
                    }
                }
                return stopLocked(
                    session = session,
                    requestedStatus = HostAutoSessionStatus.STOPPED,
                    message = "automatic session stopped after apply failure: ${failure.message.orEmpty().take(128)}",
                    observedState = HostHardwareStateReader.read(fs, hostCapabilities),
                    ownershipCandidates = ownershipCandidates,
                    modeOwnershipCandidates = if (requestedValuesMayRemain) {
                        requestMaximumPaths(request)
                    } else {
                        emptySet()
                    },
                )
            }
            // The request, rather than a later readback, defines what this session wrote. If an
            // OEM worker races the verification read, matching domains remain ours to restore
            // while mismatched domains are preserved as external changes.
            session.lastOwnedCpuMax = request.cpuMax
            session.lastOwnedGpuMax = request.gpuMax
            hostCapabilities.cpus.forEachIndexed { index, cpu ->
                session.lastOwnedMaxModes[cpu.maxPath] = autoAppliedMode(
                    session.lastOwnedMaxModes.getValue(cpu.maxPath),
                    stock = resolvedStep.cpuStock[index],
                )
                session.ownedModePaths += cpu.maxPath
            }
            if (request.gpuMax != null) hostCapabilities.gpu?.let { gpu ->
                session.lastOwnedMaxModes[gpu.maxPath] = autoAppliedMode(
                    session.lastOwnedMaxModes.getValue(gpu.maxPath),
                    stock = resolvedStep.gpuStock,
                )
                session.ownedModePaths += gpu.maxPath
            }
            HostHardwareStateReader.read(fs, hostCapabilities)
        } else {
            current
        }
        externalCeilingDrift(session, appliedState)?.let { detail ->
            return stopLocked(
                session,
                HostAutoSessionStatus.STOPPED,
                "automatic session stopped: external maximum changed after apply ($detail)",
                observedState = appliedState,
            )
        }
        return activeSnapshot(session, state = appliedState)
    }

    @Synchronized
    fun heartbeat(sessionId: String, expectedHostEpoch: Long): HostAutoSessionSnapshot {
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch) ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        val current = HostHardwareStateReader.read(fs, hostCapabilities)
        externalCeilingDrift(session, current)?.let { detail ->
            return stopLocked(
                session,
                HostAutoSessionStatus.STOPPED,
                "automatic session stopped: external maximum changed ($detail)",
                observedState = current,
            )
        }
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
        observedState: HostState? = null,
        ownershipCandidates: Map<String, Set<Long>>? = null,
        modeOwnershipCandidates: Set<String>? = null,
    ): HostAutoSessionSnapshot {
        if (active !== session) return staleOrTerminal(session.id, hostEpoch)
        val restorePlan = selectOwnedCeilings(
            session,
            observedState ?: HostHardwareStateReader.read(fs, hostCapabilities),
            ownershipCandidates,
            modeOwnershipCandidates,
        )
        active = null
        runCatching(telemetrySource::end)
        val pending = PendingRestoration(session, requestedStatus, message, restorePlan)
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
        val restoration = runCatching {
            HostCheckpointEngine(fs, hostCapabilities).restoreOwnedCeilings(
                session.checkpoint,
                pending.restorePlan,
            )
        }
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

    private fun createSession(
        request: AutoSessionRequest,
        id: String,
        checkpoint: HostHardwareCheckpoint,
        state: HostState,
        now: Long,
    ): ActiveSession {
        check(checkpointMatchesState(checkpoint, state)) {
            "frequency ceilings changed while Auto Tune was creating a session"
        }
        val cpuCeilings = hostCapabilities.cpus.map { cpu -> checkpoint.valueFor(cpu.maxPath) }
        val gpuCeiling = hostCapabilities.gpu?.let { gpu -> checkpoint.valueFor(gpu.maxPath) }
        val timeoutNanos = request.heartbeatTimeoutMs * 1_000_000L
        return ActiveSession(
            id = id,
            targetFps = request.targetFps,
            timeoutNanos = timeoutNanos,
            checkpoint = checkpoint,
            cpuLowerEnvelope = hostCapabilities.cpus.mapIndexed { index, cpu ->
                lowestCpuCeiling(cpu, cpuCeilings[index]) ?: state.cpuMax[index]
            },
            cpuCeilingEnvelope = cpuCeilings,
            gpuLowerEnvelope = hostCapabilities.gpu?.let { gpu ->
                lowestGpuCeiling(gpu, checkpoint.valueFor(gpu.maxPath))
            },
            gpuCeilingEnvelope = gpuCeiling,
            deadlineNanos = deadline(now, timeoutNanos),
            lastOwnedCpuMax = state.cpuMax,
            lastOwnedGpuMax = state.gpuMax,
            lastOwnedMaxModes = checkpoint.nodes.associate { it.path to it.mode }.toMutableMap(),
            ownedModePaths = mutableSetOf(),
        )
    }

    private fun checkpointMatchesState(
        checkpoint: HostHardwareCheckpoint,
        state: HostState,
    ): Boolean {
        val cpu = hostCapabilities.cpus.map { domain -> checkpoint.valueFor(domain.maxPath) }
        val gpu = hostCapabilities.gpu?.let { domain -> checkpoint.valueFor(domain.maxPath) }
        return cpu.all { it > 0L } && (gpu == null || gpu > 0L) &&
            state.cpuMax == cpu && state.gpuMax == gpu
    }

    private fun validateBaselineRequest(request: ApplyRequest) {
        require(request.maximumsOnly) { "Auto Tune baseline must be maximums-only" }
        require(request.cpuMax.size == hostCapabilities.cpus.size) { "CPU domain count mismatch" }
        require(request.cpuIds == hostCapabilities.cpus.map(CpuDomain::id)) { "CPU domain order mismatch" }
        if (request.gpuMax != null) {
            val gpu = hostCapabilities.gpu
                ?: throw IllegalArgumentException("GPU target requested without a GPU domain")
            require(request.gpuId == gpu.id) { "GPU identity mismatch" }
            require(request.gpuMaxPath == gpu.maxPath) { "GPU path mismatch" }
        } else {
            require(request.gpuId == null && request.gpuMaxPath == null && request.stabilizedStockCeiling == null) {
                "GPU metadata requires a baseline GPU target"
            }
        }
        // Resolve Stock aliases during preflight so malformed hints fail before telemetry or
        // hardware ownership is acquired.
        resolveHostMaximumTargets(hostCapabilities, request)
    }

    private fun requireBaselineStateMatches(
        before: HostState,
        after: HostState,
        request: ApplyRequest,
        resolved: HostResolvedMaximumTargets,
    ) {
        require(after.cpuMax.size == hostCapabilities.cpus.size) {
            "Auto Tune baseline CPU state is incomplete"
        }
        resolved.cpuAcceptedCeilings.forEachIndexed { index, accepted ->
            require(after.cpuMax[index] in accepted) {
                "Auto Tune baseline changed externally for ${hostCapabilities.cpus[index].id}"
            }
        }
        val gpu = hostCapabilities.gpu
        if (gpu == null) {
            require(after.gpuMax == null) { "Auto Tune baseline contains an unexpected GPU" }
        } else if (request.gpuMax == null) {
            require(after.gpuMax == before.gpuMax) { "Auto Tune baseline GPU changed externally" }
        } else {
            require(after.gpuMax in resolved.gpuAcceptedCeilings) {
                "Auto Tune baseline changed externally for ${gpu.id}"
            }
        }
    }

    private fun baselineOwnershipCandidates(
        before: HostState,
        request: ApplyRequest,
        resolved: HostResolvedMaximumTargets,
        confirmedState: HostState?,
        includeAcceptedCandidates: Boolean,
        rollbackOwnedValues: Map<String, Set<Long>>,
    ): Map<String, Set<Long>> = buildMap {
        hostCapabilities.cpus.forEachIndexed { index, cpu ->
            put(
                cpu.maxPath,
                buildSet {
                    before.cpuMax.getOrNull(index)?.takeIf { it > 0L }?.let(::add)
                    confirmedState?.cpuMax?.getOrNull(index)?.takeIf { it > 0L }?.let(::add)
                    if (includeAcceptedCandidates) {
                        addAll(resolved.cpuAcceptedCeilings[index].filter { it > 0L })
                    }
                    addAll(rollbackOwnedValues[cpu.maxPath].orEmpty().filter { it > 0L })
                },
            )
        }
        hostCapabilities.gpu?.let { gpu ->
            put(
                gpu.maxPath,
                buildSet {
                    if (request.gpuMax != null) {
                        before.gpuMax?.takeIf { it > 0L }?.let(::add)
                        confirmedState?.gpuMax?.takeIf { it > 0L }?.let(::add)
                        if (includeAcceptedCandidates) {
                            addAll(resolved.gpuAcceptedCeilings.filter { it > 0L })
                        }
                        addAll(rollbackOwnedValues[gpu.maxPath].orEmpty().filter { it > 0L })
                    }
                },
            )
        }
    }

    private fun requestMaximumPaths(request: ApplyRequest): Set<String> = buildSet {
        hostCapabilities.cpus.forEach { add(it.maxPath) }
        if (request.gpuMax != null) hostCapabilities.gpu?.let { add(it.maxPath) }
    }

    private fun autoAppliedMode(mode: Int, stock: Boolean): Int =
        if (stock) mode or 0x080 else mode and 0x16d

    private fun validateRequestIdentity(request: ApplyRequest) {
        require(request.cpuMax.size == hostCapabilities.cpus.size) { "CPU domain count mismatch" }
        require(request.cpuIds == hostCapabilities.cpus.map(CpuDomain::id)) { "CPU domain order mismatch" }
        val gpu = hostCapabilities.gpu
        require((request.gpuMax == null) == (gpu == null)) { "GPU domain count mismatch" }
        if (gpu != null) {
            require(request.gpuId == gpu.id) { "GPU identity mismatch" }
            require(request.gpuMaxPath == gpu.maxPath) { "GPU path mismatch" }
        }
    }

    private fun validateWithinEnvelope(session: ActiveSession, request: ApplyRequest) {
        require(!request.resetToStock) { "automatic steps cannot request a stock reset" }
        require(request.stabilizedStockCeiling == null) { "automatic steps cannot override the GPU stock ceiling" }
        request.cpuMax.forEachIndexed { index, requested ->
            val cpu = hostCapabilities.cpus[index]
            val lower = session.cpuLowerEnvelope[index]
            val envelope = session.cpuCeilingEnvelope[index]
            require(
                requested > 0L && requested >= lower && requested <= envelope &&
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
            val lower = session.gpuLowerEnvelope
            require(
                requested > 0L && (lower == null || requested >= lower) && requested <= envelope &&
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

    private fun externalCeilingDrift(
        session: ActiveSession,
        state: HostState = HostHardwareStateReader.read(fs, hostCapabilities),
    ): String? {
        if (state.cpuMax.size != session.lastOwnedCpuMax.size) return "CPU topology"
        state.cpuMax.forEachIndexed { index, actual ->
            val expected = session.lastOwnedCpuMax[index]
            if (actual != expected) return "${hostCapabilities.cpus[index].id}: $expected->$actual"
        }
        if (state.gpuMax != session.lastOwnedGpuMax) {
            return "${hostCapabilities.gpu?.id ?: "GPU"}: ${session.lastOwnedGpuMax}->${state.gpuMax}"
        }
        session.lastOwnedMaxModes.forEach { (path, expected) ->
            val actual = fs.mode(path)
            if (actual != expected) return "$path mode: $expected->$actual"
        }
        return null
    }

    private fun selectOwnedCeilings(
        session: ActiveSession,
        state: HostState,
        candidateOverrides: Map<String, Set<Long>>? = null,
        modeOwnershipOverrides: Set<String>? = null,
    ): HostCeilingRestorePlan {
        val owned = mutableMapOf<String, Long>()
        val unresolved = mutableMapOf<String, Set<Long>>()
        val ownedModes = session.ownedModePaths.toMutableSet().apply {
            modeOwnershipOverrides?.let(::addAll)
        }
        hostCapabilities.cpus.forEachIndexed { index, cpu ->
            val candidates = if (candidateOverrides?.containsKey(cpu.maxPath) == true) {
                candidateOverrides.getValue(cpu.maxPath)
            } else {
                setOfNotNull(session.lastOwnedCpuMax.getOrNull(index))
            }
            val actual = state.cpuMax.getOrNull(index)
            when {
                (actual == null || actual < 0L) && candidates.isNotEmpty() -> unresolved[cpu.maxPath] = candidates
                actual != null && actual in candidates -> owned[cpu.maxPath] = actual
            }
        }
        hostCapabilities.gpu?.let { gpu ->
            val candidates = if (candidateOverrides?.containsKey(gpu.maxPath) == true) {
                candidateOverrides.getValue(gpu.maxPath)
            } else {
                setOfNotNull(session.lastOwnedGpuMax)
            }
            val actual = state.gpuMax
            when {
                (actual == null || actual < 0L) && candidates.isNotEmpty() -> unresolved[gpu.maxPath] = candidates
                actual != null && actual in candidates -> owned[gpu.maxPath] = actual
            }
        }
        return HostCeilingRestorePlan(owned, unresolved, ownedModes)
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

    private fun lowestCpuCeiling(cpu: CpuDomain, sessionCeiling: Long): Long? =
        (cpu.supportedFrequencies.asSequence() + cpu.minimumCandidates.asSequence())
            .filter { it > 0L && it <= sessionCeiling }
            .minOrNull()

    private fun lowestGpuCeiling(gpu: GpuDomain, sessionCeiling: Long): Long =
        gpu.supportedFrequencies.asSequence()
            .filter { it > 0L && it <= sessionCeiling }
            .minOrNull()
            ?: gpu.observedMin.takeIf { it > 0L && it <= sessionCeiling }
            ?: sessionCeiling

    private fun HostHardwareCheckpoint.valueFor(path: String): Long =
        nodes.firstOrNull { it.path == path }?.value ?: error("checkpoint is missing $path")
}
