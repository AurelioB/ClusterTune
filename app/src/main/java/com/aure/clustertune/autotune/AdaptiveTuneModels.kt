package com.aure.clustertune.autotune

import com.aure.clustertune.model.MIN_AUTO_TUNE_TARGET_FPS

const val MIN_ADAPTIVE_TARGET_FPS = MIN_AUTO_TUNE_TARGET_FPS

/**
 * Controller tuning parameters. Time values use the same monotonic nanosecond clock as
 * [AdaptiveTuneSample.timestampNanos].
 */
data class AdaptiveTuneConfig(
    val targetFps: Int,
    val warmupSampleCount: Int = 5,
    val healthyQualificationSampleCount: Int = 3,
    val trialWatchSampleCount: Int = 2,
    val trialFreezeNanos: Long = 30_000_000_000L,
    val recoveryFpsRatio: Double = 0.96,
    val healthyFpsRatio: Double = 0.99,
    val healthyP95BudgetMultiplier: Double = 1.25,
    val healthySlowFrameRatio: Double = 0.02,
    val recoverySlowFrameRatio: Double = 0.10,
    val highUtilization: Double = 0.82,
) {
    init {
        require(targetFps >= MIN_ADAPTIVE_TARGET_FPS) { "Target FPS must be positive" }
        require(warmupSampleCount >= 0) { "Warmup sample count must not be negative" }
        require(healthyQualificationSampleCount > 0) {
            "Healthy qualification sample count must be positive"
        }
        require(trialWatchSampleCount > 0) { "Trial watch sample count must be positive" }
        require(trialFreezeNanos >= 0L) { "Trial freeze must not be negative" }
        require(recoveryFpsRatio in 0.5..1.0) { "Recovery FPS ratio is out of range" }
        require(healthyFpsRatio in recoveryFpsRatio..1.1) { "Healthy FPS ratio is out of range" }
        require(healthyP95BudgetMultiplier >= 1.0) { "P95 budget multiplier must be at least one" }
        require(healthySlowFrameRatio in 0.0..1.0) { "Slow-frame ratio is out of range" }
        require(recoverySlowFrameRatio in healthySlowFrameRatio..1.0) {
            "Recovery slow-frame ratio must be at least the healthy threshold"
        }
        require(highUtilization in 0.0..1.0) { "High-utilization threshold is out of range" }
    }
}

/** One cpufreq policy. CPU frequencies are kHz. */
data class AdaptiveCpuPolicy(
    val policyId: Int,
    val availableCeilingsKHz: List<Long>,
    val baseCeilingKHz: Long,
    /** Whether the controller may select this policy for trim or recovery moves. */
    val allowsAdaptiveAdjustment: Boolean = true,
) {
    init {
        require(baseCeilingKHz > 0L) { "CPU base ceiling must be positive" }
    }
}

/** Optional GPU frequency domain. GPU frequencies are Hz. */
data class AdaptiveGpuDomain(
    val id: String = "gpu",
    val availableCeilingsHz: List<Long>,
    val baseCeilingHz: Long,
) {
    init {
        require(id.isNotBlank()) { "GPU domain id must not be blank" }
        require(baseCeilingHz > 0L) { "GPU base ceiling must be positive" }
    }
}

/** The maximum envelope Auto Tune may use. It never changes minimums or governors. */
data class AdaptiveTuneEnvelope(
    val cpuPolicies: List<AdaptiveCpuPolicy>,
    val gpu: AdaptiveGpuDomain? = null,
) {
    init {
        require(cpuPolicies.isNotEmpty()) { "At least one CPU policy is required" }
        require(cpuPolicies.map { it.policyId }.distinct().size == cpuPolicies.size) {
            "CPU policy ids must be unique"
        }
    }
}

/** Complete set of maximum-frequency ceilings produced by the controller. */
data class AdaptiveFrequencyCeilings(
    val cpuKHz: Map<Int, Long>,
    val gpuHz: Long? = null,
)

/** Frame metrics for the selected application layer. */
data class AdaptiveFrameMetrics(
    val fps: Double,
    val p95FrameTimeMillis: Double? = null,
    /** Fraction in the inclusive range 0..1. */
    val slowFrameRatio: Double? = null,
    val isStale: Boolean = false,
)

/** One observation, timestamped with an injected monotonic clock. Loads are fractions from 0..1. */
data class AdaptiveTuneSample(
    val timestampNanos: Long,
    val frames: AdaptiveFrameMetrics?,
    val cpuLoad: Map<Int, Double?> = emptyMap(),
    val gpuBusy: Double? = null,
)

sealed interface AdaptiveActuator {
    data class CpuPolicy(val policyId: Int) : AdaptiveActuator
    data class Gpu(val id: String) : AdaptiveActuator
}

/** A signed OPP movement for exactly one actuator. */
data class AdaptiveCeilingChange(
    val actuator: AdaptiveActuator,
    val fromCeiling: Long,
    val toCeiling: Long,
    /** Positive raises performance; negative trims it. */
    val stepDelta: Int,
)

enum class AdaptiveTuneStatus {
    WARMING_UP,
    WAITING_FOR_FRAMES,
    MONITORING,
    RECOVERING,
    OPTIMIZING,
    WATCHING_TRIAL,
    FROZEN,
    STOPPED,
}

enum class AdaptiveTuneReason {
    WARMUP,
    HEALTHY_QUALIFYING,
    FRAME_DATA_STALE,
    NON_MONOTONIC_SAMPLE,
    HEALTHY_AT_FLOOR,
    HEALTHY_NO_TRIM_CANDIDATE,
    WITHIN_TARGET_BAND,
    CPU_BOTTLENECK_RECOVERY,
    GPU_BOTTLENECK_RECOVERY,
    RECOVERY_AT_BASE,
    EFFICIENCY_TRIM,
    TRIAL_WATCH,
    TRIAL_ACCEPTED,
    TRIAL_REGRESSION,
    REQUESTED_STOP,
}

/** A controller result that can be rendered directly and orchestrated without inspecting internals. */
sealed interface AdaptiveTuneDecision {
    val status: AdaptiveTuneStatus
    val reason: AdaptiveTuneReason
    val ceilings: AdaptiveFrequencyCeilings

    data class Hold(
        override val status: AdaptiveTuneStatus,
        override val reason: AdaptiveTuneReason,
        override val ceilings: AdaptiveFrequencyCeilings,
        val warmupSamplesRemaining: Int = 0,
        val healthySamplesRemaining: Int = 0,
    ) : AdaptiveTuneDecision

    data class Apply(
        override val status: AdaptiveTuneStatus,
        override val reason: AdaptiveTuneReason,
        override val ceilings: AdaptiveFrequencyCeilings,
        val change: AdaptiveCeilingChange,
    ) : AdaptiveTuneDecision

    data class Stop(
        override val reason: AdaptiveTuneReason,
        override val ceilings: AdaptiveFrequencyCeilings,
    ) : AdaptiveTuneDecision {
        override val status: AdaptiveTuneStatus = AdaptiveTuneStatus.STOPPED
    }
}
