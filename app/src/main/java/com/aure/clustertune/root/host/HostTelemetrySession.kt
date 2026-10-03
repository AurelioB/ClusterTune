package com.aure.clustertune.root.host

import java.util.UUID

/**
 * Owns the host's read-only performance monitor. Unlike [HostAutoSessionController], this
 * controller never captures, applies, restores, chmods, or otherwise claims hardware state.
 */
class HostTelemetrySessionController(
    private val hostCapabilities: HostCapabilities,
    private val telemetrySource: HostTelemetrySource,
    private val hostEpoch: Long,
    private val clock: HostMonotonicClock = SystemHostMonotonicClock,
) {
    private data class ActiveSession(
        val id: String,
        val targetFps: Int,
        val timeoutNanos: Long,
        var deadlineNanos: Long,
        var sequence: Long = 0L,
        var latestTelemetry: HostAutoTelemetry? = null,
    )

    private var active: ActiveSession? = null
    private var terminal: HostTelemetrySessionSnapshot? = null

    companion object {
        private const val MIN_HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val MAX_HEARTBEAT_TIMEOUT_MS = 120_000L
        private val TERMINAL_STATUSES = setOf(
            HostTelemetrySessionStatus.STOPPED,
            HostTelemetrySessionStatus.EXPIRED,
            HostTelemetrySessionStatus.UNAVAILABLE,
        )

        @JvmStatic
        fun production(
            capabilities: HostCapabilities,
            fs: HostFilesystem,
            hostEpoch: Long,
        ): HostTelemetrySessionController = HostTelemetrySessionController(
            hostCapabilities = capabilities,
            telemetrySource = SystemHostTelemetrySource(fs, capabilities),
            hostEpoch = hostEpoch,
        )
    }

    /** Starts a new monitor, replacing only an older monitor—not an Auto Tune session. */
    @Synchronized
    @JvmOverloads
    fun start(
        request: TelemetrySessionRequest,
        telemetryAvailable: Boolean = true,
    ): HostTelemetrySessionSnapshot {
        expireLocked()
        val targetPackage = request.packageName?.takeUnless(String::isBlank)
        if (targetPackage == null) {
            require(request.targetFps == 0) { "device-only telemetry requires a zero target FPS" }
        } else {
            require(HostTelemetryParsers.isValidPackageName(targetPackage)) { "invalid target package" }
            require(request.targetFps > 0) { "frame telemetry requires a positive target FPS" }
        }
        require(request.heartbeatTimeoutMs in MIN_HEARTBEAT_TIMEOUT_MS..MAX_HEARTBEAT_TIMEOUT_MS) {
            "heartbeat timeout must be between $MIN_HEARTBEAT_TIMEOUT_MS and $MAX_HEARTBEAT_TIMEOUT_MS ms"
        }
        if (!telemetryAvailable) {
            active?.let {
                stopLocked(
                    it,
                    HostTelemetrySessionStatus.UNAVAILABLE,
                    "performance telemetry is held by Auto Tune",
                )
            }
            return unavailable(request.targetFps, "performance telemetry is held by Auto Tune")
        }
        active?.let {
            stopLocked(it, HostTelemetrySessionStatus.STOPPED, "replaced by a new telemetry session")
        }
        val support = telemetrySource.capabilities()
        if (targetPackage != null && !support.autoSessionSupported) {
            return unavailable(
                request.targetFps,
                support.unsupportedReason ?: "performance telemetry is unavailable",
            )
        }
        val started = if (targetPackage == null) {
            // SystemHostTelemetrySource intentionally reports frame_session_not_started while
            // continuing to sample CPU, GPU, and thermal nodes.
            Result.success(Unit)
        } else {
            telemetrySource.begin(targetPackage, request.targetFps)
        }
        if (started.isFailure) {
            runCatching(telemetrySource::end)
            return unavailable(
                request.targetFps,
                started.exceptionOrNull()?.message ?: "frame telemetry is unavailable",
            )
        }
        val timeoutNanos = request.heartbeatTimeoutMs * 1_000_000L
        val session = ActiveSession(
            id = UUID.randomUUID().toString(),
            targetFps = request.targetFps,
            timeoutNanos = timeoutNanos,
            deadlineNanos = deadline(clock.nanoTime(), timeoutNanos),
        )
        active = session
        terminal = null
        return activeSnapshot(session)
    }

    /**
     * Reads and renews exactly [sessionId] at [expectedHostEpoch]. A stale handle can never
     * sample, renew, or stop a newer monitor.
     */
    @Synchronized
    @JvmOverloads
    fun read(
        sessionId: String,
        expectedHostEpoch: Long,
        afterSequence: Long = -1L,
        telemetryAvailable: Boolean = true,
    ): HostTelemetrySessionSnapshot {
        require(afterSequence >= -1L) { "invalid telemetry sequence" }
        expireLocked()
        val session = resolveActive(sessionId, expectedHostEpoch)
            ?: return staleOrTerminal(sessionId, expectedHostEpoch)
        if (!telemetryAvailable) {
            return stopLocked(
                session,
                HostTelemetrySessionStatus.UNAVAILABLE,
                "performance telemetry is held by Auto Tune",
            )
        }
        touch(session)
        if (session.latestTelemetry != null && afterSequence >= 0L && afterSequence < session.sequence) {
            return activeSnapshot(session, session.latestTelemetry)
        }
        val raw = runCatching(telemetrySource::sample).getOrElse { failure ->
            unavailableSample(failure)
        }
        session.sequence++
        val telemetry = raw.toWireTelemetry(session.sequence)
        session.latestTelemetry = telemetry
        return activeSnapshot(session, telemetry)
    }

    /** Stops only the exact generation-safe handle supplied by the client. */
    @Synchronized
    fun stop(sessionId: String, expectedHostEpoch: Long): HostTelemetrySessionSnapshot {
        expireLocked()
        resolveActive(sessionId, expectedHostEpoch)?.let { session ->
            return stopLocked(session, HostTelemetrySessionStatus.STOPPED, "telemetry session stopped")
        }
        return staleOrTerminal(sessionId, expectedHostEpoch)
    }

    /** Internal lifecycle cleanup for lease loss, host shutdown, or Auto Tune ownership. */
    @Synchronized
    fun stopCurrent(
        status: HostTelemetrySessionStatus,
        reason: String,
    ): HostTelemetrySessionSnapshot? {
        require(status in TERMINAL_STATUSES) { "invalid terminal telemetry status" }
        val session = active ?: return null
        return stopLocked(session, status, reason)
    }

    @Synchronized
    fun expireIfNeeded(): HostTelemetrySessionSnapshot? {
        val session = active ?: return null
        expireLocked()
        return terminal.takeIf { active !== session }
    }

    @Synchronized
    fun current(): HostTelemetrySessionSnapshot {
        expireLocked()
        return active?.let { activeSnapshot(it, it.latestTelemetry) }
            ?: terminal
            ?: HostTelemetrySessionSnapshot(
                sessionId = null,
                hostEpoch = hostEpoch,
                status = HostTelemetrySessionStatus.STOPPED,
                targetFps = 0,
                message = "no telemetry session",
            )
    }

    private fun expireLocked() {
        val session = active ?: return
        if (clock.nanoTime() >= session.deadlineNanos) {
            stopLocked(
                session,
                HostTelemetrySessionStatus.EXPIRED,
                "telemetry session heartbeat expired",
            )
        }
    }

    private fun stopLocked(
        session: ActiveSession,
        status: HostTelemetrySessionStatus,
        message: String,
    ): HostTelemetrySessionSnapshot {
        if (active !== session) return staleOrTerminal(session.id, hostEpoch)
        active = null
        runCatching(telemetrySource::end)
        return HostTelemetrySessionSnapshot(
            sessionId = session.id,
            hostEpoch = hostEpoch,
            status = status,
            targetFps = session.targetFps,
            telemetry = session.latestTelemetry,
            message = message.take(HostProtocol.MAX_METADATA_LENGTH),
        ).also { terminal = it }
    }

    private fun resolveActive(sessionId: String, expectedHostEpoch: Long): ActiveSession? =
        active?.takeIf { it.id == sessionId && expectedHostEpoch == hostEpoch }

    private fun staleOrTerminal(
        sessionId: String,
        expectedHostEpoch: Long,
    ): HostTelemetrySessionSnapshot {
        terminal?.takeIf {
            expectedHostEpoch == hostEpoch && it.sessionId == sessionId
        }?.let { return it }
        return HostTelemetrySessionSnapshot(
            sessionId = sessionId,
            hostEpoch = hostEpoch,
            status = HostTelemetrySessionStatus.STALE,
            targetFps = 0,
            message = if (expectedHostEpoch != hostEpoch) {
                "telemetry session belongs to a different host epoch"
            } else {
                "telemetry session is stale"
            },
        )
    }

    private fun unavailable(targetFps: Int, message: String): HostTelemetrySessionSnapshot =
        HostTelemetrySessionSnapshot(
            sessionId = null,
            hostEpoch = hostEpoch,
            status = HostTelemetrySessionStatus.UNAVAILABLE,
            targetFps = targetFps,
            message = message.take(HostProtocol.MAX_METADATA_LENGTH),
        ).also { terminal = it }

    private fun activeSnapshot(
        session: ActiveSession,
        telemetry: HostAutoTelemetry? = null,
    ): HostTelemetrySessionSnapshot = HostTelemetrySessionSnapshot(
        sessionId = session.id,
        hostEpoch = hostEpoch,
        status = HostTelemetrySessionStatus.ACTIVE,
        targetFps = session.targetFps,
        telemetry = telemetry,
    )

    private fun touch(session: ActiveSession) {
        session.deadlineNanos = deadline(clock.nanoTime(), session.timeoutNanos)
    }

    private fun unavailableSample(failure: Throwable): HostRawTelemetry = HostRawTelemetry(
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

    private fun HostRawTelemetry.toWireTelemetry(sequence: Long): HostAutoTelemetry = HostAutoTelemetry(
        sequence = sequence,
        timestampNanos = timestampNanos,
        frameBackend = frameBackend?.take(HostProtocol.MAX_METADATA_LENGTH),
        frameConfidencePermille = frameConfidencePermille.coerceIn(0, 1000),
        frameLayer = frameLayer?.take(HostProtocol.MAX_METADATA_LENGTH),
        frameCount = frameCount.coerceIn(0, 100_000),
        fpsMilli = fpsMilli?.coerceIn(0, 1_000_000),
        frameTimeP95Nanos = frameTimeP95Nanos?.takeIf { it >= 0L },
        slowFrameRatioPermille = slowFrameRatioPermille?.coerceIn(0, 1000),
        frameStale = frameStale,
        cpuLoadPermille = List(hostCapabilities.cpus.size.coerceIn(0, 64)) { index ->
            cpuLoadPermille.getOrNull(index)?.coerceIn(0, 1000)
        },
        cpuClockKHz = List(hostCapabilities.cpus.size.coerceIn(0, 64)) { index ->
            cpuClockKHz.getOrNull(index)?.takeIf { it >= 0L }
        },
        gpuBusyPermille = gpuBusyPermille?.coerceIn(0, 1000),
        gpuClockHz = gpuClockHz?.takeIf { it >= 0L },
        thermal = thermal.take(HostProtocol.MAX_THERMAL_READINGS).map {
            it.copy(type = it.type.take(64))
        },
        unsupportedMetrics = unsupportedMetrics.map { it.take(HostProtocol.MAX_METADATA_LENGTH) }
            .distinct().take(HostProtocol.MAX_UNSUPPORTED_METRICS),
    )

    private fun deadline(now: Long, timeoutNanos: Long): Long =
        if (now > Long.MAX_VALUE - timeoutNanos) Long.MAX_VALUE else now + timeoutNanos
}
