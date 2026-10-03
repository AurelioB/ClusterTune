package com.aure.clustertune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** Immutable, display-ready input for the compact performance HUD. */
@Immutable
data class PerformanceHudUiModel(
    val framesPerSecond: Double? = null,
    val p95FrameTimeMillis: Double? = null,
    val frameRateHistoryFps: List<Float?> = emptyList(),
    val frameRateGraphMaximumFps: Float? = null,
    val cpuLoadPercent: Double? = null,
    val cpuClockKHz: Long? = null,
    val cpuLoadHistoryPercent: List<Float?> = emptyList(),
    val gpuBusyPercent: Double? = null,
    val gpuClockHz: Long? = null,
    val gpuBusyHistoryPercent: List<Float?> = emptyList(),
    val maxTemperatureCelsius: Double? = null,
    val oemPerformanceProfile: String? = null,
    val fanProfile: String? = null,
    val tuneMode: PerformanceHudTuneMode? = null,
)

/** The mutually exclusive ClusterTune state shown at the bottom of the HUD. */
@Immutable
sealed interface PerformanceHudTuneMode {
    @Immutable
    data class FixedProfile(val profileName: String) : PerformanceHudTuneMode

    @Immutable
    data class AutoTune(
        val packageName: String,
        val targetFps: Int,
        val targetRange: IntRange,
        val configuredTargetFps: Int = targetFps,
    ) : PerformanceHudTuneMode
}

object PerformanceHudTestTags {
    const val OVERLAY = "performance_hud_overlay"
    const val FRAME_ROW = "performance_hud_frame_row"
    const val FRAME_GRAPH = "performance_hud_frame_graph"
    const val CPU_ROW = "performance_hud_cpu_row"
    const val CPU_GRAPH = "performance_hud_cpu_graph"
    const val GPU_ROW = "performance_hud_gpu_row"
    const val GPU_GRAPH = "performance_hud_gpu_graph"
    const val TEMPERATURE_ROW = "performance_hud_temperature_row"
    const val OEM_PROFILE_ROW = "performance_hud_oem_profile_row"
    const val FAN_PROFILE_ROW = "performance_hud_fan_profile_row"
    const val TUNE_MODE_ROW = "performance_hud_tune_mode_row"
    const val WAITING = "performance_hud_waiting"
    const val AUTO_TUNE_SLIDER = "performance_hud_auto_tune_slider"
}

private val HudBackground = Color(0xF516181B)
private val HudBorder = Color(0x40FFFFFF)
private val HudText = Color(0xFFF0F3F4)
private val HudLabel = Color(0xFFA7B0B5)
private val HudAccent = Color(0xFF6DE4C2)
private val HudFrameGraph = Color(0xFF66D9A6)
private val HudCpuGraph = Color(0xFF42A5F5)
private val HudGpuGraph = Color(0xFFEF5350)
private val HudCompactControlWidth = 72.dp
private val HudCompactControlHeight = 16.dp
private val HudCompactThumbWidth = 3.dp
private val HudCompactThumbHeight = 14.dp

internal const val PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT = 30

private val HudTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 13.sp,
    fontWeight = FontWeight.Bold,
)

/**
 * A non-draggable, wrap-content HUD. The Auto Tune target is its only control.
 *
 * [onAutoTuneTargetCommit] is called once after a slider interaction finishes,
 * never for intermediate draft values.
 */
@Composable
fun PerformanceHudOverlay(
    model: PerformanceHudUiModel,
    onAutoTuneTargetCommit: (String, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoTune = model.tuneMode as? PerformanceHudTuneMode.AutoTune
    val bounds = autoTune?.normalizedBounds()
    var targetDraft by remember(autoTune?.packageName) {
        mutableIntStateOf(autoTune?.boundedTarget(bounds) ?: 0)
    }
    var gestureExpectedTargetFps by remember(autoTune?.packageName) {
        mutableStateOf<Int?>(null)
    }

    LaunchedEffect(autoTune?.targetFps, bounds?.first, bounds?.last) {
        if (autoTune != null && bounds != null) {
            targetDraft = autoTune.boundedTarget(bounds)
        }
    }

    Surface(
        modifier = modifier
            .widthIn(min = 168.dp, max = 244.dp)
            .testTag(PerformanceHudTestTags.OVERLAY),
        shape = RoundedCornerShape(9.dp),
        color = HudBackground,
        contentColor = HudText,
        border = BorderStroke(0.5.dp, HudBorder),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val telemetryRows = performanceHudTelemetryRows(model)
            if (telemetryRows.isEmpty()) {
                Text(
                    text = "Waiting for telemetry",
                    modifier = Modifier.testTag(PerformanceHudTestTags.WAITING),
                    color = HudLabel,
                    style = HudTextStyle,
                )
            } else {
                telemetryRows.forEach { row ->
                    when (row.testTag) {
                        PerformanceHudTestTags.FRAME_ROW -> HudRow(
                            row = row,
                            graphSamples = performanceHudFpsGraphSamples(
                                samples = model.frameRateHistoryFps,
                                maximumFps = model.frameRateGraphMaximumFps,
                            ),
                            graphColor = HudFrameGraph,
                            graphTestTag = PerformanceHudTestTags.FRAME_GRAPH,
                            graphContentDescription = "FPS history",
                        )
                        PerformanceHudTestTags.CPU_ROW -> HudRow(
                            row = row,
                            graphSamples = model.cpuLoadHistoryPercent,
                            graphColor = HudCpuGraph,
                            graphTestTag = PerformanceHudTestTags.CPU_GRAPH,
                            graphContentDescription = "CPU usage history",
                        )
                        PerformanceHudTestTags.GPU_ROW -> HudRow(
                            row = row,
                            graphSamples = model.gpuBusyHistoryPercent,
                            graphColor = HudGpuGraph,
                            graphTestTag = PerformanceHudTestTags.GPU_GRAPH,
                            graphContentDescription = "GPU usage history",
                        )
                        else -> HudRow(row)
                    }
                }
            }

            performanceHudContextRows(model, targetDraft).forEach { row ->
                if (row.testTag == PerformanceHudTestTags.TUNE_MODE_ROW && autoTune != null && bounds != null) {
                    HudRow(
                        row = row,
                        extraContent = {
                            key(autoTune.packageName) {
                                CompactAutoTuneSlider(
                                    value = targetDraft,
                                    bounds = bounds,
                                    onValueChange = {
                                        if (gestureExpectedTargetFps == null) {
                                            gestureExpectedTargetFps = autoTune.configuredTargetFps
                                        }
                                        targetDraft = it
                                    },
                                    onValueChangeFinished = {
                                        gestureExpectedTargetFps?.let { expectedTargetFps ->
                                            onAutoTuneTargetCommit(
                                                autoTune.packageName,
                                                expectedTargetFps,
                                                targetDraft,
                                            )
                                        }
                                        gestureExpectedTargetFps = null
                                        targetDraft = autoTune.boundedTarget(bounds)
                                    },
                                )
                            }
                        },
                    )
                } else {
                    HudRow(row)
                }
            }
        }
    }
}

@Composable
private fun HudRow(
    row: PerformanceHudRow,
    graphSamples: List<Float?> = emptyList(),
    graphColor: Color? = null,
    graphTestTag: String? = null,
    graphContentDescription: String? = null,
    extraContent: (@Composable () -> Unit)? = null,
) {
    val boundedGraphSamples = performanceHudGraphSamples(graphSamples)
    val showGraph = graphColor != null &&
        graphTestTag != null &&
        graphContentDescription != null &&
        boundedGraphSamples.any { it != null }
    Row(
        modifier = Modifier.testTag(row.testTag),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = row.label,
            modifier = Modifier.width(48.dp),
            color = HudLabel,
            style = HudTextStyle,
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = row.value,
                color = if (row.accent) HudAccent else HudText,
                style = HudTextStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showGraph) {
                PerformanceHudPercentGraph(
                    samples = boundedGraphSamples,
                    color = requireNotNull(graphColor),
                    testTag = requireNotNull(graphTestTag),
                    contentDescription = requireNotNull(graphContentDescription),
                )
            }
            extraContent?.invoke()
        }
    }
}

/** A graph-sized FPS control with integer values and no decorative tick marks. */
@Composable
private fun CompactAutoTuneSlider(
    value: Int,
    bounds: IntRange,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val lower = minOf(bounds.first, bounds.last)
    val upper = maxOf(bounds.first, bounds.last).coerceAtLeast(lower)
    val enabled = lower < upper
    val boundedValue = value.coerceIn(lower, upper)
    val rangeLength = (upper - lower).toFloat()
    val fraction = if (enabled) (boundedValue - lower) / rangeLength else 0f
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val thumbWidthPx = with(density) { HudCompactThumbWidth.toPx() }
    val latestValue by rememberUpdatedState(boundedValue)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
    var usableTrackWidthPx by remember { mutableFloatStateOf(1f) }
    var dragValue by remember(lower, upper) { mutableFloatStateOf(boundedValue.toFloat()) }
    val draggableState = rememberDraggableState { deltaPixels ->
        if (!enabled || usableTrackWidthPx <= 0f) return@rememberDraggableState
        dragValue = (dragValue + (deltaPixels / usableTrackWidthPx) * rangeLength).coerceIn(
            lower.toFloat(),
            upper.toFloat(),
        )
        latestOnValueChange(dragValue.roundToInt().coerceIn(lower, upper))
    }

    Canvas(
        modifier = Modifier
            .requiredWidth(HudCompactControlWidth)
            .requiredHeight(HudCompactControlHeight)
            .testTag(PerformanceHudTestTags.AUTO_TUNE_SLIDER)
            .onSizeChanged { size ->
                usableTrackWidthPx = (size.width.toFloat() - thumbWidthPx).coerceAtLeast(1f)
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = enabled,
                reverseDirection = layoutDirection == LayoutDirection.Rtl,
                onDragStarted = { dragValue = latestValue.toFloat() },
                onDragStopped = { latestOnValueChangeFinished() },
            )
            .pointerInput(lower, upper, enabled, layoutDirection) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val rawFraction = ((offset.x - thumbWidthPx / 2f) / usableTrackWidthPx)
                        .coerceIn(0f, 1f)
                    val directedFraction = if (layoutDirection == LayoutDirection.Rtl) {
                        1f - rawFraction
                    } else {
                        rawFraction
                    }
                    latestOnValueChange(
                        (lower + directedFraction * rangeLength).roundToInt().coerceIn(lower, upper),
                    )
                    latestOnValueChangeFinished()
                }
            }
            .semantics {
                contentDescription = "Auto Tune FPS target"
                stateDescription = "$boundedValue FPS"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = boundedValue.toFloat(),
                    range = lower.toFloat()..upper.toFloat(),
                    steps = (upper - lower - 1).coerceAtLeast(0),
                )
                if (enabled) {
                    setProgress { requestedValue ->
                        latestOnValueChange(requestedValue.roundToInt().coerceIn(lower, upper))
                        latestOnValueChangeFinished()
                        true
                    }
                }
            },
    ) {
        val centerY = size.height / 2f
        val thumbWidth = HudCompactThumbWidth.toPx()
        val thumbHeight = HudCompactThumbHeight.toPx()
        val trackStart = thumbWidth / 2f
        val trackEnd = (size.width - thumbWidth / 2f).coerceAtLeast(trackStart)
        val directedFraction = if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
        val thumbX = trackStart + (trackEnd - trackStart) * directedFraction
        val trackWidth = 2.dp.toPx()
        val inactiveColor = HudBorder.copy(alpha = if (enabled) 0.9f else 0.55f)
        val activeColor = HudAccent.copy(alpha = if (enabled) 1f else 0.65f)
        val activeStart = if (layoutDirection == LayoutDirection.Rtl) trackEnd else trackStart

        drawLine(
            color = inactiveColor,
            start = Offset(trackStart, centerY),
            end = Offset(trackEnd, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = activeColor,
            start = Offset(activeStart, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(thumbX - thumbWidth / 2f, centerY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f),
        )
    }
}

@Composable
private fun PerformanceHudPercentGraph(
    samples: List<Float?>,
    color: Color,
    testTag: String,
    contentDescription: String,
) {
    val segments = performanceHudGraphSegments(samples)
    Canvas(
        modifier = Modifier
            .width(72.dp)
            .height(16.dp)
            .testTag(testTag)
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (samples.size < 2 || segments.none { it.size >= 2 }) return@Canvas
        val xStep = size.width / (samples.size - 1)
        val mainWidth = 1.5.dp.toPx()
        val glowStyle = Stroke(
            width = mainWidth * 2f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val mainStyle = Stroke(
            width = mainWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        val path = Path()
        segments.forEach { segment ->
            if (segment.size < 2) return@forEach
            segment.forEachIndexed { pointIndex, point ->
                val x = point.sampleIndex * xStep
                val y = size.height - ((point.percent / 100f) * size.height)
                if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        drawPath(path = path, color = color.copy(alpha = 0.4f), style = glowStyle)
        drawPath(path = path, color = color, style = mainStyle)
    }
}

internal data class PerformanceHudGraphPoint(
    val sampleIndex: Int,
    val percent: Float,
)

/** Keeps the HUD graph on a fixed 0–100 scale and a GameNative-sized 30-sample window. */
internal fun performanceHudGraphSamples(samples: List<Float?>): List<Float?> =
    samples.takeLast(PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT).map { value ->
        value
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.coerceAtMost(100f)
    }

/** Maps frame-rate history onto the same fixed-height graph using the active display ceiling. */
internal fun performanceHudFpsGraphSamples(
    samples: List<Float?>,
    maximumFps: Float?,
): List<Float?> {
    val ceiling = maximumFps?.takeIf { it.isFinite() && it > 0f } ?: return emptyList()
    return samples.takeLast(PERFORMANCE_HUD_GRAPH_SAMPLE_COUNT).map { value ->
        value
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.let { (it / ceiling * 100f).coerceAtMost(100f) }
    }
}

/** Missing samples deliberately split the sparkline instead of bridging telemetry gaps. */
internal fun performanceHudGraphSegments(
    samples: List<Float?>,
): List<List<PerformanceHudGraphPoint>> {
    val segments = mutableListOf<MutableList<PerformanceHudGraphPoint>>()
    samples.forEachIndexed { sampleIndex, percent ->
        if (percent == null) return@forEachIndexed
        val activeSegment = segments.lastOrNull()
            ?.takeIf { segment -> segment.last().sampleIndex == sampleIndex - 1 }
            ?: mutableListOf<PerformanceHudGraphPoint>().also(segments::add)
        activeSegment += PerformanceHudGraphPoint(sampleIndex, percent)
    }
    return segments
}

internal data class PerformanceHudRow(
    val label: String,
    val value: String,
    val testTag: String,
    val accent: Boolean = false,
)

internal fun performanceHudTelemetryRows(model: PerformanceHudUiModel): List<PerformanceHudRow> = buildList {
    val frameParts = buildList {
        model.framesPerSecond.validNonNegative()?.let { add("${formatHudDecimal(it)} FPS") }
        model.p95FrameTimeMillis.validNonNegative()?.let { add("P95 ${formatHudDecimal(it)} ms") }
    }
    if (frameParts.isNotEmpty()) {
        add(PerformanceHudRow("FRAME", frameParts.joinToString(" · "), PerformanceHudTestTags.FRAME_ROW))
    } else if (
        performanceHudFpsGraphSamples(
            samples = model.frameRateHistoryFps,
            maximumFps = model.frameRateGraphMaximumFps,
        ).any { it != null }
    ) {
        // Keep recent frame history visible while the current sample is a deliberate gap.
        add(PerformanceHudRow("FRAME", "—", PerformanceHudTestTags.FRAME_ROW))
    }

    val cpuParts = buildList {
        model.cpuLoadPercent.validPercent()?.let { add(formatHudPercent(it)) }
        model.cpuClockKHz?.takeIf { it > 0L }?.let { add(formatHudCpuClock(it)) }
    }
    if (cpuParts.isNotEmpty()) {
        add(PerformanceHudRow("CPU", cpuParts.joinToString(" · "), PerformanceHudTestTags.CPU_ROW))
    }

    val gpuParts = buildList {
        model.gpuBusyPercent.validPercent()?.let { add(formatHudPercent(it)) }
        model.gpuClockHz?.takeIf { it > 0L }?.let { add(formatHudGpuClock(it)) }
    }
    if (gpuParts.isNotEmpty()) {
        add(PerformanceHudRow("GPU", gpuParts.joinToString(" · "), PerformanceHudTestTags.GPU_ROW))
    }

    model.maxTemperatureCelsius.validNonNegative()?.let {
        add(PerformanceHudRow("TEMP", formatHudTemperature(it), PerformanceHudTestTags.TEMPERATURE_ROW))
    }
}

internal fun performanceHudContextRows(
    model: PerformanceHudUiModel,
    autoTuneTargetOverride: Int? = null,
): List<PerformanceHudRow> = buildList {
    model.oemPerformanceProfile.cleanLabel()?.let {
        add(PerformanceHudRow("OEM", it, PerformanceHudTestTags.OEM_PROFILE_ROW))
    }
    model.fanProfile.cleanLabel()?.let {
        add(PerformanceHudRow("FAN", it, PerformanceHudTestTags.FAN_PROFILE_ROW))
    }
    when (val mode = model.tuneMode) {
        is PerformanceHudTuneMode.FixedProfile -> mode.profileName.cleanLabel()?.let {
            add(PerformanceHudRow("PROFILE", it, PerformanceHudTestTags.TUNE_MODE_ROW, accent = true))
        }
        is PerformanceHudTuneMode.AutoTune -> {
            val bounds = mode.normalizedBounds()
            val target = autoTuneTargetOverride?.coerceIn(bounds) ?: mode.boundedTarget(bounds)
            add(PerformanceHudRow("AUTO", "$target FPS", PerformanceHudTestTags.TUNE_MODE_ROW, accent = true))
        }
        null -> Unit
    }
}

internal fun formatHudDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

internal fun formatHudPercent(value: Double): String = "${value.roundToInt().coerceIn(0, 100)}%"

internal fun formatHudCpuClock(clockKHz: Long): String = when {
    clockKHz >= 1_000_000L -> String.format(Locale.US, "%.2f GHz", clockKHz / 1_000_000.0)
    else -> "${(clockKHz / 1_000.0).roundToInt()} MHz"
}

internal fun formatHudGpuClock(clockHz: Long): String = when {
    clockHz >= 1_000_000_000L -> String.format(Locale.US, "%.2f GHz", clockHz / 1_000_000_000.0)
    else -> "${(clockHz / 1_000_000.0).roundToInt()} MHz"
}

internal fun formatHudTemperature(celsius: Double): String = "${celsius.roundToInt()}°C"

private fun Double?.validNonNegative(): Double? = this?.takeIf { it.isFinite() && it >= 0.0 }

private fun Double?.validPercent(): Double? = this?.takeIf { it.isFinite() && it in 0.0..100.0 }

private fun String?.cleanLabel(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun PerformanceHudTuneMode.AutoTune.normalizedBounds(): IntRange {
    val lower = minOf(targetRange.first, targetRange.last).coerceAtLeast(1)
    val upper = maxOf(targetRange.first, targetRange.last).coerceAtLeast(lower)
    return lower..upper
}

private fun PerformanceHudTuneMode.AutoTune.boundedTarget(bounds: IntRange?): Int =
    targetFps.coerceIn(bounds ?: normalizedBounds())
