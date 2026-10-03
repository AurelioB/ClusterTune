package com.aure.clustertune.overlay

import com.aure.clustertune.autotune.AdaptiveTuneRuntimeState
import com.aure.clustertune.model.MIN_AUTO_TUNE_TARGET_FPS
import com.aure.clustertune.root.host.HostAutoTelemetry
import com.aure.clustertune.ui.PerformanceHudTuneMode
import com.aure.clustertune.ui.PerformanceHudUiModel

internal fun buildPerformanceHudUiModel(
    telemetry: HostAutoTelemetry?,
    autoTune: AdaptiveTuneRuntimeState,
    autoTunePackageName: String?,
    configuredAutoTuneTargetFps: Int?,
    displayRefreshRateFps: Int?,
    effectiveProfileName: String?,
    vendorState: PerformanceHudVendorState,
    graphHistory: PerformanceHudGraphHistory = PerformanceHudGraphHistory(),
): PerformanceHudUiModel {
    val autoTarget = configuredAutoTuneTargetFps?.takeIf { it > 0 }
        ?: autoTune.targetFps?.takeIf { autoTune.active && it > 0 }
    val tuneMode = if (autoTarget != null && !autoTunePackageName.isNullOrBlank()) {
        val maximum = displayRefreshRateFps?.takeIf { it > 0 } ?: autoTarget
        PerformanceHudTuneMode.AutoTune(
            packageName = autoTunePackageName,
            targetFps = autoTarget.coerceIn(MIN_AUTO_TUNE_TARGET_FPS, maximum),
            targetRange = MIN_AUTO_TUNE_TARGET_FPS..maximum,
            configuredTargetFps = autoTarget,
        )
    } else {
        effectiveProfileName?.takeIf(String::isNotBlank)?.let(PerformanceHudTuneMode::FixedProfile)
    }

    return if (autoTune.active) {
        PerformanceHudUiModel(
            framesPerSecond = autoTune.performanceHudFramesPerSecond(),
            p95FrameTimeMillis = autoTune.p95FrameTimeMillis?.takeUnless { autoTune.frameStale },
            frameRateHistoryFps = graphHistory.frameRateFps,
            frameRateGraphMaximumFps = performanceHudFrameRateGraphMaximumFps(
                displayRefreshRateFps = displayRefreshRateFps,
                tuneMode = tuneMode,
            ),
            cpuLoadPercent = autoTune.performanceHudCpuLoadPercent(),
            cpuClockKHz = autoTune.cpuClockKHz.values.filterNotNull().maxOrNull(),
            gpuBusyPercent = autoTune.performanceHudGpuBusyPercent(),
            gpuClockHz = autoTune.gpuClockHz,
            maxTemperatureCelsius = maxRelevantTemperatureCelsius(autoTune.thermalMilliCelsius),
            oemPerformanceProfile = vendorState.performanceProfile,
            fanProfile = vendorState.fanProfile,
            tuneMode = tuneMode,
            cpuLoadHistoryPercent = graphHistory.cpuLoadPercent,
            gpuBusyHistoryPercent = graphHistory.gpuBusyPercent,
        )
    } else {
        PerformanceHudUiModel(
            framesPerSecond = telemetry.performanceHudFramesPerSecond(),
            p95FrameTimeMillis = telemetry?.frameTimeP95Nanos
                ?.takeIf {
                    it > 0L &&
                        telemetry.frameConfidencePermille >= 250 &&
                        !telemetry.frameStale
                }
                ?.div(1_000_000.0),
            frameRateHistoryFps = graphHistory.frameRateFps,
            frameRateGraphMaximumFps = performanceHudFrameRateGraphMaximumFps(
                displayRefreshRateFps = displayRefreshRateFps,
                tuneMode = tuneMode,
            ),
            cpuLoadPercent = telemetry.performanceHudCpuLoadPercent(),
            cpuClockKHz = telemetry?.cpuClockKHz?.filterNotNull()?.maxOrNull(),
            gpuBusyPercent = telemetry.performanceHudGpuBusyPercent(),
            gpuClockHz = telemetry?.gpuClockHz,
            maxTemperatureCelsius = maxRelevantTemperatureCelsius(
                telemetry?.thermal.orEmpty().associate { reading ->
                    reading.type to reading.temperatureMilliCelsius
                },
            ),
            oemPerformanceProfile = vendorState.performanceProfile,
            fanProfile = vendorState.fanProfile,
            tuneMode = tuneMode,
            cpuLoadHistoryPercent = graphHistory.cpuLoadPercent,
            gpuBusyHistoryPercent = graphHistory.gpuBusyPercent,
        )
    }
}

internal const val PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT = 30

internal data class PerformanceHudGraphSource(
    val streamId: String,
    val packageName: String?,
)

internal data class PerformanceHudGraphHistory(
    val source: PerformanceHudGraphSource? = null,
    val frameRateFps: List<Float?> = emptyList(),
    val cpuLoadPercent: List<Float?> = emptyList(),
    val gpuBusyPercent: List<Float?> = emptyList(),
)

/**
 * Records exactly one aligned graph point per advancing host sample. Auxiliary HUD updates
 * (profile, fan, refresh rate, recomposition) cannot manufacture history entries.
 */
internal class PerformanceHudGraphAccumulator(
    private val capacity: Int = PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT,
) {
    init {
        require(capacity > 0)
    }

    private var source: PerformanceHudGraphSource? = null
    private var lastSampleIdentity: Long? = null
    private val frameRateFps = ArrayDeque<Float?>()
    private val cpuLoadPercent = ArrayDeque<Float?>()
    private val gpuBusyPercent = ArrayDeque<Float?>()

    fun record(
        source: PerformanceHudGraphSource,
        sampleIdentity: Long,
        framesPerSecond: Double?,
        cpuLoadPercent: Double?,
        gpuBusyPercent: Double?,
    ): PerformanceHudGraphHistory {
        if (this.source != source) {
            this.source = source
            lastSampleIdentity = null
            this.frameRateFps.clear()
            this.cpuLoadPercent.clear()
            this.gpuBusyPercent.clear()
        }
        val previousIdentity = lastSampleIdentity
        if (previousIdentity != null && sampleIdentity <= previousIdentity) return snapshot()

        lastSampleIdentity = sampleIdentity
        this.frameRateFps.addLast(framesPerSecond.validHudFrameRatePoint())
        this.cpuLoadPercent.addLast(cpuLoadPercent.validHudGraphPoint())
        this.gpuBusyPercent.addLast(gpuBusyPercent.validHudGraphPoint())
        while (this.frameRateFps.size > capacity) this.frameRateFps.removeFirst()
        while (this.cpuLoadPercent.size > capacity) this.cpuLoadPercent.removeFirst()
        while (this.gpuBusyPercent.size > capacity) this.gpuBusyPercent.removeFirst()
        return snapshot()
    }

    fun snapshot(): PerformanceHudGraphHistory = PerformanceHudGraphHistory(
        source = source,
        frameRateFps = frameRateFps.toList(),
        cpuLoadPercent = cpuLoadPercent.toList(),
        gpuBusyPercent = gpuBusyPercent.toList(),
    )
}

internal fun AdaptiveTuneRuntimeState.performanceHudGraphSource(): PerformanceHudGraphSource? =
    sessionGeneration?.takeIf { active }?.let { generation ->
        PerformanceHudGraphSource("auto:$generation", packageName)
    }

internal fun PerformanceHudTelemetryState.performanceHudGraphSource(
    packageName: String?,
): PerformanceHudGraphSource? = streamId?.let { id ->
    PerformanceHudGraphSource("monitor:$id", packageName)
}

internal fun AdaptiveTuneRuntimeState.performanceHudFramesPerSecond(): Double? = measuredFps
    ?.takeUnless { frameStale }
    ?.takeIf { it.isFinite() && it >= 0.0 }

internal fun HostAutoTelemetry?.performanceHudFramesPerSecond(): Double? = this?.fpsMilli
    ?.takeIf { it > 0 && frameConfidencePermille >= 250 && !frameStale }
    ?.div(1_000.0)

internal fun AdaptiveTuneRuntimeState.performanceHudCpuLoadPercent(): Double? = cpuLoad.values
    .filterNotNull()
    .maxOrNull()
    ?.times(100.0)
    .validHudGraphPercent()

internal fun AdaptiveTuneRuntimeState.performanceHudGpuBusyPercent(): Double? = gpuBusy
    ?.times(100.0)
    .validHudGraphPercent()

internal fun HostAutoTelemetry?.performanceHudCpuLoadPercent(): Double? = this?.cpuLoadPermille
    ?.filterNotNull()
    ?.maxOrNull()
    ?.coerceIn(0, 1_000)
    ?.div(10.0)

internal fun HostAutoTelemetry?.performanceHudGpuBusyPercent(): Double? = this?.gpuBusyPermille
    ?.coerceIn(0, 1_000)
    ?.div(10.0)

private fun Double?.validHudGraphPercent(): Double? = this?.takeIf {
    it.isFinite() && it in 0.0..100.0
}

private fun Double?.validHudGraphPoint(): Float? = validHudGraphPercent()?.toFloat()

private fun Double?.validHudFrameRatePoint(): Float? = this
    ?.takeIf { it.isFinite() && it >= 0.0 }
    ?.toFloat()

private fun performanceHudFrameRateGraphMaximumFps(
    displayRefreshRateFps: Int?,
    tuneMode: PerformanceHudTuneMode?,
): Float? = displayRefreshRateFps
    ?.takeIf { it > 0 }
    ?.toFloat()
    ?: (tuneMode as? PerformanceHudTuneMode.AutoTune)
        ?.targetRange
        ?.let { maxOf(it.first, it.last) }
        ?.takeIf { it > 0 }
        ?.toFloat()

private val relevantThermalTokens = listOf(
    "cpu", "gpu", "soc", "ap", "cluster", "little", "big", "silver", "gold",
)

internal fun maxRelevantTemperatureCelsius(readings: Map<String, Long>): Double? {
    val valid = readings.filterValues { value -> value in -20_000L..150_000L }
    val relevant = valid.filterKeys { name ->
        val normalized = name.lowercase()
        relevantThermalTokens.any(normalized::contains)
    }
    return relevant.values.maxOrNull()?.div(1_000.0)
}
