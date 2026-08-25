package com.aure.clustertune.autotune

import kotlin.math.max

/**
 * Pure, deterministic maximum-frequency controller.
 *
 * The caller owns sampling, persistence, and hardware I/O. Each [step] consumes one monotonic
 * sample and returns either no mutation, one complete ceiling envelope with exactly one changed
 * actuator, or a stop request. The controller never changes minimum frequencies or governors.
 */
class AdaptiveFrequencyController(
    val config: AdaptiveTuneConfig,
    envelope: AdaptiveTuneEnvelope,
) {
    private enum class TrialKind { RAISE, TRIM }

    private enum class HealthState { HEALTHY, NEUTRAL, UNHEALTHY }

    private data class FrameHealth(
        val fps: Double,
        val p95FrameTimeMillis: Double?,
        val slowFrameRatio: Double?,
        val state: HealthState,
    )

    private data class DomainState(
        val actuator: AdaptiveActuator,
        val steps: List<Long>,
        val baseIndex: Int,
        var currentIndex: Int,
        val allowsAdaptiveAdjustment: Boolean,
    )

    private data class Trial(
        val kind: TrialKind,
        val domain: DomainState,
        val previousIndex: Int,
        val trialIndex: Int,
        val baseline: FrameHealth,
        val observedSamples: Int = 0,
        val consecutiveRegressions: Int = 0,
        val totalSamples: Long = 0L,
    )

    private val domains: List<DomainState> = buildList {
        envelope.cpuPolicies.sortedBy { it.policyId }.forEach { cpu ->
            val steps = normalizedSteps(cpu.availableCeilingsKHz, cpu.baseCeilingKHz)
            add(
                DomainState(
                    actuator = AdaptiveActuator.CpuPolicy(cpu.policyId),
                    steps = steps,
                    baseIndex = steps.lastIndex,
                    currentIndex = steps.lastIndex,
                    allowsAdaptiveAdjustment = cpu.allowsAdaptiveAdjustment,
                ),
            )
        }
        envelope.gpu?.let { gpu ->
            val steps = normalizedSteps(gpu.availableCeilingsHz, gpu.baseCeilingHz)
            add(
                DomainState(
                    actuator = AdaptiveActuator.Gpu(gpu.id),
                    steps = steps,
                    baseIndex = steps.lastIndex,
                    currentIndex = steps.lastIndex,
                    allowsAdaptiveAdjustment = true,
                ),
            )
        }
    }

    val baseCeilings: AdaptiveFrequencyCeilings = ceilings()

    private var lastSampleTimestampNanos: Long? = null
    private var sessionStartedAtNanos: Long? = null
    private var lastFreshFrameAtNanos: Long? = null
    private var warmupSamplesSeen = 0
    private var healthyQualificationSamplesSeen = 0
    private var trial: Trial? = null
    private var stoppedReason: AdaptiveTuneReason? = null
    private val raiseDeprioritizedUntil = mutableMapOf<AdaptiveActuator, Long>()
    private val raiseRegressionFrozenUntil = mutableMapOf<AdaptiveActuator, Long>()
    private val trimFrozenUntil = mutableMapOf<AdaptiveActuator, Long>()

    fun currentCeilings(): AdaptiveFrequencyCeilings = ceilings()

    fun stop(): AdaptiveTuneDecision.Stop {
        stoppedReason = AdaptiveTuneReason.REQUESTED_STOP
        trial = null
        healthyQualificationSamplesSeen = 0
        return AdaptiveTuneDecision.Stop(AdaptiveTuneReason.REQUESTED_STOP, ceilings())
    }

    fun step(sample: AdaptiveTuneSample): AdaptiveTuneDecision {
        stoppedReason?.let { return AdaptiveTuneDecision.Stop(it, ceilings()) }

        val previousTimestamp = lastSampleTimestampNanos
        if (sample.timestampNanos < 0L || (previousTimestamp != null && sample.timestampNanos < previousTimestamp)) {
            stoppedReason = AdaptiveTuneReason.NON_MONOTONIC_SAMPLE
            trial = null
            return AdaptiveTuneDecision.Stop(AdaptiveTuneReason.NON_MONOTONIC_SAMPLE, ceilings())
        }
        if (previousTimestamp != null && sample.timestampNanos == previousTimestamp) {
            return hold(AdaptiveTuneStatus.MONITORING, AdaptiveTuneReason.NON_MONOTONIC_SAMPLE)
        }
        lastSampleTimestampNanos = sample.timestampNanos
        if (sessionStartedAtNanos == null) sessionStartedAtNanos = sample.timestampNanos
        expireFreezes(sample.timestampNanos)

        val frame = sample.frames
            ?.takeIf(::isFreshFrame)
            ?.let(::frameHealth)
        if (frame != null) {
            lastFreshFrameAtNanos = sample.timestampNanos
        } else {
            val graceAnchor = lastFreshFrameAtNanos ?: sessionStartedAtNanos ?: sample.timestampNanos
            if (sample.timestampNanos - graceAnchor >= config.missingFrameGraceNanos) {
                stoppedReason = AdaptiveTuneReason.FRAME_DATA_STALE
                trial = null
                healthyQualificationSamplesSeen = 0
                return AdaptiveTuneDecision.Stop(AdaptiveTuneReason.FRAME_DATA_STALE, ceilings())
            }
        }

        if (frame == null) {
            healthyQualificationSamplesSeen = 0
            return hold(AdaptiveTuneStatus.WAITING_FOR_FRAMES, AdaptiveTuneReason.FRAME_DATA_GRACE)
        }

        if (warmupSamplesSeen < config.warmupSampleCount) {
            warmupSamplesSeen += 1
            healthyQualificationSamplesSeen = 0
            return AdaptiveTuneDecision.Hold(
                status = AdaptiveTuneStatus.WARMING_UP,
                reason = AdaptiveTuneReason.WARMUP,
                ceilings = ceilings(),
                warmupSamplesRemaining = config.warmupSampleCount - warmupSamplesSeen,
            )
        }

        trial?.let { active ->
            healthyQualificationSamplesSeen = 0
            return evaluateTrial(active, frame, sample)
        }

        return when (frame.state) {
            HealthState.UNHEALTHY -> {
                healthyQualificationSamplesSeen = 0
                recover(frame, sample)
            }
            HealthState.HEALTHY -> qualifyHealthyThenTrim(frame, sample)
            HealthState.NEUTRAL -> {
                healthyQualificationSamplesSeen = 0
                hold(AdaptiveTuneStatus.MONITORING, AdaptiveTuneReason.WITHIN_TARGET_BAND)
            }
        }
    }

    private fun qualifyHealthyThenTrim(
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        healthyQualificationSamplesSeen += 1
        val remaining = config.healthyQualificationSampleCount - healthyQualificationSamplesSeen
        if (remaining > 0) {
            return AdaptiveTuneDecision.Hold(
                status = AdaptiveTuneStatus.MONITORING,
                reason = AdaptiveTuneReason.HEALTHY_QUALIFYING,
                ceilings = ceilings(),
                healthySamplesRemaining = remaining,
            )
        }
        healthyQualificationSamplesSeen = 0
        return trim(frame, sample)
    }

    private fun recover(
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        val headroom = domains.filter { domain ->
            domain.allowsAdaptiveAdjustment && domain.currentIndex < domain.baseIndex
        }
        if (headroom.isEmpty()) {
            return hold(AdaptiveTuneStatus.MONITORING, AdaptiveTuneReason.RECOVERY_AT_BASE)
        }

        val unfrozenHeadroom = headroom.filter { domain ->
            !isFrozen(raiseRegressionFrozenUntil, domain.actuator, sample.timestampNanos)
        }
        if (unfrozenHeadroom.isEmpty()) {
            return hold(AdaptiveTuneStatus.FROZEN, AdaptiveTuneReason.RECOVERY_FROZEN)
        }

        val saturatedHeadroom = unfrozenHeadroom.filter { domain ->
            utilization(domain, sample)?.let { it >= config.highUtilization } == true
        }
        val eligibleHeadroom = saturatedHeadroom.ifEmpty { unfrozenHeadroom }
        val preferred = eligibleHeadroom.filter { domain ->
            !isFrozen(raiseDeprioritizedUntil, domain.actuator, sample.timestampNanos)
        }
        val candidates = preferred.ifEmpty {
            // No-gain is not proof that a raise was useless: CPU/GPU and cluster bottlenecks can
            // be coupled. When every domain has recently produced no isolated gain, keep opening
            // the envelope instead of waiting for the deprioritization timeout. Choosing the
            // oldest deadline rotates away from the domain most recently tested. A measured-hot
            // domain remains a harder constraint than deprioritization, so a cold policy cannot
            // displace the only saturated recovery candidate.
            val oldestDeadline = eligibleHeadroom.minOf { domain ->
                raiseDeprioritizedUntil[domain.actuator] ?: Long.MIN_VALUE
            }
            eligibleHeadroom.filter { domain ->
                (raiseDeprioritizedUntil[domain.actuator] ?: Long.MIN_VALUE) == oldestDeadline
            }
        }

        val domain = selectRecoveryDomain(candidates, sample)
        val stepCount = recoveryStepCount(frame)
        val targetIndex = (domain.currentIndex + stepCount).coerceAtMost(domain.baseIndex)
        val reason = when (domain.actuator) {
            is AdaptiveActuator.CpuPolicy -> AdaptiveTuneReason.CPU_BOTTLENECK_RECOVERY
            is AdaptiveActuator.Gpu -> AdaptiveTuneReason.GPU_BOTTLENECK_RECOVERY
        }
        return applyTrialMove(
            domain = domain,
            targetIndex = targetIndex,
            kind = TrialKind.RAISE,
            baseline = frame,
            status = AdaptiveTuneStatus.RECOVERING,
            reason = reason,
        )
    }

    private fun trim(
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        val adjustable = domains.filter { domain ->
            domain.allowsAdaptiveAdjustment &&
                domain.currentIndex > 0 &&
                !isFrozen(trimFrozenUntil, domain.actuator, sample.timestampNanos)
        }
        if (adjustable.isEmpty()) {
            val atFloor = domains
                .filter(DomainState::allowsAdaptiveAdjustment)
                .all { it.currentIndex == 0 }
            return hold(
                status = if (atFloor) AdaptiveTuneStatus.MONITORING else AdaptiveTuneStatus.FROZEN,
                reason = if (atFloor) {
                    AdaptiveTuneReason.HEALTHY_AT_FLOOR
                } else {
                    AdaptiveTuneReason.HEALTHY_NO_TRIM_CANDIDATE
                },
            )
        }

        val domain = selectTrimDomain(adjustable, sample)
        return applyTrialMove(
            domain = domain,
            targetIndex = domain.currentIndex - 1,
            kind = TrialKind.TRIM,
            baseline = frame,
            status = AdaptiveTuneStatus.OPTIMIZING,
            reason = AdaptiveTuneReason.EFFICIENCY_TRIM,
        )
    }

    private fun evaluateTrial(
        active: Trial,
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        return when (active.kind) {
            TrialKind.TRIM -> evaluateTrimTrial(active, frame, sample)
            TrialKind.RAISE -> evaluateRaiseTrial(active, frame, sample)
        }
    }

    private fun evaluateTrimTrial(
        active: Trial,
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        val totalSamples = active.totalSamples + 1L
        if (isRegression(active.baseline, frame)) {
            val consecutiveRegressions = active.consecutiveRegressions + 1
            if (
                consecutiveRegressions >= TRIM_REGRESSION_SAMPLE_COUNT ||
                totalSamples >= trialMaximumSamples()
            ) {
                return rollbackTrim(active, sample)
            }
            // A single noisy frame is not enough evidence to undo a trim, and it must not count
            // toward accepting the trial either.
            trial = active.copy(
                observedSamples = 0,
                consecutiveRegressions = consecutiveRegressions,
                totalSamples = totalSamples,
            )
            return hold(AdaptiveTuneStatus.WATCHING_TRIAL, AdaptiveTuneReason.TRIAL_WATCH)
        }

        val observed = active.observedSamples + 1
        if (observed >= config.trialWatchSampleCount) {
            trial = null
            return hold(AdaptiveTuneStatus.MONITORING, AdaptiveTuneReason.TRIAL_ACCEPTED)
        }
        if (totalSamples >= trialMaximumSamples()) {
            return rollbackTrim(active, sample)
        }
        trial = active.copy(
            observedSamples = observed,
            consecutiveRegressions = 0,
            totalSamples = totalSamples,
        )
        return hold(AdaptiveTuneStatus.WATCHING_TRIAL, AdaptiveTuneReason.TRIAL_WATCH)
    }

    private fun rollbackTrim(
        active: Trial,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision.Apply {
        trial = null
        trimFrozenUntil[active.domain.actuator] = freezeDeadline(sample.timestampNanos)
        return applyMove(
            domain = active.domain,
            targetIndex = active.previousIndex,
            status = AdaptiveTuneStatus.FROZEN,
            reason = AdaptiveTuneReason.TRIAL_REGRESSION,
        )
    }

    private fun trialMaximumSamples(): Long =
        config.trialWatchSampleCount.toLong() * TRIAL_MAX_SAMPLE_MULTIPLIER

    private fun evaluateRaiseTrial(
        active: Trial,
        frame: FrameHealth,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision {
        val totalSamples = active.totalSamples + 1L
        if (isRegression(active.baseline, frame)) {
            val consecutiveRegressions = active.consecutiveRegressions + 1
            if (
                consecutiveRegressions >= TRIM_REGRESSION_SAMPLE_COUNT ||
                totalSamples >= trialMaximumSamples()
            ) {
                return rollbackRaise(active, sample)
            }
            trial = active.copy(
                observedSamples = 0,
                consecutiveRegressions = consecutiveRegressions,
                totalSamples = totalSamples,
            )
            return hold(AdaptiveTuneStatus.WATCHING_TRIAL, AdaptiveTuneReason.TRIAL_WATCH)
        }

        if (isImprovement(active.baseline, frame)) {
            trial = null
            raiseDeprioritizedUntil.remove(active.domain.actuator)
            return hold(AdaptiveTuneStatus.MONITORING, AdaptiveTuneReason.TRIAL_ACCEPTED)
        }

        val observed = active.observedSamples + 1
        if (observed >= config.trialWatchSampleCount) {
            trial = null
            raiseDeprioritizedUntil[active.domain.actuator] = freezeDeadline(sample.timestampNanos)
            // A bounded raise cannot violate the assigned envelope. Keep it while performance is
            // unhealthy because an isolated raise may only help after another coupled domain is
            // opened too; the next recovery attempt is steered to another domain when possible.
            return hold(AdaptiveTuneStatus.RECOVERING, AdaptiveTuneReason.TRIAL_NO_GAIN)
        }
        if (totalSamples >= trialMaximumSamples()) {
            return rollbackRaise(active, sample)
        }
        trial = active.copy(
            observedSamples = observed,
            consecutiveRegressions = 0,
            totalSamples = totalSamples,
        )
        return hold(AdaptiveTuneStatus.WATCHING_TRIAL, AdaptiveTuneReason.TRIAL_WATCH)
    }

    private fun rollbackRaise(
        active: Trial,
        sample: AdaptiveTuneSample,
    ): AdaptiveTuneDecision.Apply {
        trial = null
        raiseDeprioritizedUntil.remove(active.domain.actuator)
        raiseRegressionFrozenUntil[active.domain.actuator] = freezeDeadline(sample.timestampNanos)
        return applyMove(
            domain = active.domain,
            targetIndex = active.previousIndex,
            status = AdaptiveTuneStatus.FROZEN,
            reason = AdaptiveTuneReason.TRIAL_REGRESSION,
        )
    }

    private fun selectRecoveryDomain(
        candidates: List<DomainState>,
        sample: AdaptiveTuneSample,
    ): DomainState {
        val scored = candidates.map { domain -> domain to utilization(domain, sample) }
        val known = scored.filter { it.second != null }
        if (known.isNotEmpty()) {
            val saturated = known.filter { (_, load) -> load != null && load >= config.highUtilization }
            return (saturated.ifEmpty { known })
                .maxWithOrNull(
                    compareBy<Pair<DomainState, Double?>> { it.second ?: -1.0 }
                        .thenBy { recoveryTieBreak(it.first.actuator) },
                )!!
                .first
        }
        return candidates.maxWithOrNull(
            compareBy<DomainState> { it.baseIndex - it.currentIndex }
                .thenBy { recoveryTieBreak(it.actuator) },
        )!!
    }

    private fun selectTrimDomain(
        candidates: List<DomainState>,
        sample: AdaptiveTuneSample,
    ): DomainState {
        return candidates.minWithOrNull(
            compareBy<DomainState> { utilization(it, sample) ?: 0.5 }
                .thenBy { trimTieBreak(it.actuator) },
        )!!
    }

    private fun utilization(domain: DomainState, sample: AdaptiveTuneSample): Double? {
        val raw = when (val actuator = domain.actuator) {
            is AdaptiveActuator.CpuPolicy -> sample.cpuLoad[actuator.policyId]
            is AdaptiveActuator.Gpu -> sample.gpuBusy
        }
        return raw?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    }

    private fun recoveryStepCount(frame: FrameHealth): Int {
        val fpsRatio = frame.fps / config.targetFps.toDouble()
        val frameBudget = frameBudgetMillis()
        val severePacing = frame.p95FrameTimeMillis?.let { it > frameBudget * 2.0 } == true ||
            frame.slowFrameRatio?.let { it > 0.30 } == true
        val moderatePacing = frame.p95FrameTimeMillis?.let { it > frameBudget * 1.5 } == true ||
            frame.slowFrameRatio?.let { it > 0.20 } == true
        return when {
            fpsRatio < 0.75 || severePacing -> 3
            fpsRatio < 0.90 || moderatePacing -> 2
            else -> 1
        }
    }

    private fun applyTrialMove(
        domain: DomainState,
        targetIndex: Int,
        kind: TrialKind,
        baseline: FrameHealth,
        status: AdaptiveTuneStatus,
        reason: AdaptiveTuneReason,
    ): AdaptiveTuneDecision.Apply {
        val previousIndex = domain.currentIndex
        val decision = applyMove(domain, targetIndex, status, reason)
        trial = Trial(
            kind = kind,
            domain = domain,
            previousIndex = previousIndex,
            trialIndex = targetIndex,
            baseline = baseline,
        )
        return decision
    }

    private fun applyMove(
        domain: DomainState,
        targetIndex: Int,
        status: AdaptiveTuneStatus,
        reason: AdaptiveTuneReason,
    ): AdaptiveTuneDecision.Apply {
        val boundedIndex = targetIndex.coerceIn(0, domain.baseIndex)
        val previousIndex = domain.currentIndex
        check(previousIndex != boundedIndex) { "Adaptive move must change one domain" }
        val from = domain.steps[previousIndex]
        domain.currentIndex = boundedIndex
        val to = domain.steps[boundedIndex]
        return AdaptiveTuneDecision.Apply(
            status = status,
            reason = reason,
            ceilings = ceilings(),
            change = AdaptiveCeilingChange(
                actuator = domain.actuator,
                fromCeiling = from,
                toCeiling = to,
                stepDelta = boundedIndex - previousIndex,
            ),
        )
    }

    private fun frameHealth(frame: AdaptiveFrameMetrics): FrameHealth {
        val p95 = frame.p95FrameTimeMillis?.takeIf { it.isFinite() && it > 0.0 }
        val slowRatio = frame.slowFrameRatio?.takeIf { it.isFinite() && it in 0.0..1.0 }
        val p95Healthy = p95?.let { it <= frameBudgetMillis() * config.healthyP95BudgetMultiplier } != false
        val slowRatioHealthy = slowRatio?.let { it <= config.healthySlowFrameRatio } != false
        val pacingHealthy = p95Healthy && slowRatioHealthy
        val pacingUnhealthy = !p95Healthy ||
            slowRatio?.let { it > config.recoverySlowFrameRatio } == true
        val fpsHealthy = frame.fps >= config.targetFps * config.healthyFpsRatio
        val fpsUnhealthy = frame.fps < config.targetFps * config.recoveryFpsRatio
        val state = when {
            fpsUnhealthy || pacingUnhealthy -> HealthState.UNHEALTHY
            fpsHealthy && pacingHealthy -> HealthState.HEALTHY
            else -> HealthState.NEUTRAL
        }
        return FrameHealth(frame.fps, p95, slowRatio, state)
    }

    private fun isFreshFrame(frame: AdaptiveFrameMetrics): Boolean =
        !frame.isStale && frame.fps.isFinite() && frame.fps > 0.0

    private fun isRegression(baseline: FrameHealth, current: FrameHealth): Boolean {
        if (baseline.state != HealthState.UNHEALTHY && current.state == HealthState.UNHEALTHY) return true
        val fpsDrop = max(1.0, config.targetFps * 0.03)
        if (current.fps <= baseline.fps - fpsDrop) return true
        if (baseline.p95FrameTimeMillis != null && current.p95FrameTimeMillis != null &&
            current.p95FrameTimeMillis >= baseline.p95FrameTimeMillis * 1.15
        ) {
            return true
        }
        if (baseline.slowFrameRatio != null && current.slowFrameRatio != null &&
            current.slowFrameRatio >= baseline.slowFrameRatio + 0.04
        ) {
            return true
        }
        return false
    }

    private fun isImprovement(baseline: FrameHealth, current: FrameHealth): Boolean {
        if (baseline.state == HealthState.UNHEALTHY && current.state != HealthState.UNHEALTHY) return true
        val fpsGain = max(1.0, config.targetFps * 0.02)
        if (current.fps >= baseline.fps + fpsGain) return true
        if (baseline.p95FrameTimeMillis != null && current.p95FrameTimeMillis != null &&
            current.p95FrameTimeMillis <= baseline.p95FrameTimeMillis * 0.90
        ) {
            return true
        }
        if (baseline.slowFrameRatio != null && current.slowFrameRatio != null &&
            current.slowFrameRatio <= baseline.slowFrameRatio - 0.03
        ) {
            return true
        }
        return false
    }

    private fun frameBudgetMillis(): Double = 1_000.0 / config.targetFps

    private fun expireFreezes(timestampNanos: Long) {
        raiseDeprioritizedUntil.entries.removeAll { (_, until) -> until <= timestampNanos }
        raiseRegressionFrozenUntil.entries.removeAll { (_, until) -> until <= timestampNanos }
        trimFrozenUntil.entries.removeAll { (_, until) -> until <= timestampNanos }
    }

    private fun isFrozen(
        freezes: Map<AdaptiveActuator, Long>,
        actuator: AdaptiveActuator,
        timestampNanos: Long,
    ): Boolean = (freezes[actuator] ?: Long.MIN_VALUE) > timestampNanos

    private fun freezeDeadline(timestampNanos: Long): Long {
        val duration = config.trialFreezeNanos
        return if (Long.MAX_VALUE - timestampNanos < duration) Long.MAX_VALUE else timestampNanos + duration
    }

    private fun hold(
        status: AdaptiveTuneStatus,
        reason: AdaptiveTuneReason,
    ): AdaptiveTuneDecision.Hold = AdaptiveTuneDecision.Hold(status, reason, ceilings())

    private fun ceilings(): AdaptiveFrequencyCeilings {
        val cpu = domains.mapNotNull { domain ->
            val policy = domain.actuator as? AdaptiveActuator.CpuPolicy
            policy?.policyId?.let { it to domain.steps[domain.currentIndex] }
        }.toMap()
        val gpu = domains.firstOrNull { it.actuator is AdaptiveActuator.Gpu }
            ?.let { it.steps[it.currentIndex] }
        return AdaptiveFrequencyCeilings(cpuKHz = cpu, gpuHz = gpu)
    }

    private fun recoveryTieBreak(actuator: AdaptiveActuator): Int = when (actuator) {
        is AdaptiveActuator.CpuPolicy -> -actuator.policyId
        is AdaptiveActuator.Gpu -> Int.MAX_VALUE
    }

    private fun trimTieBreak(actuator: AdaptiveActuator): String = when (actuator) {
        is AdaptiveActuator.CpuPolicy -> "0:${actuator.policyId.toString().padStart(10, '0')}"
        is AdaptiveActuator.Gpu -> "1:${actuator.id}"
    }

    private companion object {
        const val TRIM_REGRESSION_SAMPLE_COUNT = 2
        const val TRIAL_MAX_SAMPLE_MULTIPLIER = 3L

        fun normalizedSteps(raw: List<Long>, base: Long): List<Long> =
            (raw.asSequence().filter { it > 0L && it <= base } + sequenceOf(base))
                .distinct()
                .sorted()
                .toList()
    }
}
