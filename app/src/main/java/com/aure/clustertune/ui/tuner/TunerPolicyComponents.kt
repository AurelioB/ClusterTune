package com.aure.clustertune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.GpuPolicyInfo
import com.aure.clustertune.ui.designsystem.component.CtRowSurface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.aure.clustertune.ui.designsystem.component.rememberCtAdjustable
import com.aure.clustertune.ui.designsystem.component.animatedScale
import com.aure.clustertune.ui.designsystem.component.ctAdjustable
import androidx.compose.animation.core.animateDpAsState

@Composable
internal fun TunerPolicyCard(
    policy: CpuPolicyInfo,
    selectedValue: Int,
    onValueChanged: (Int) -> Unit,
    compactMode: Boolean = false,
    displayFrequenciesAsPercent: Boolean = false,
    actualValue: Int = selectedValue,
    focusRequester: FocusRequester? = null,
) {
    val supported = policy.supportedFrequencies.filter { it <= policy.selectableMaxFreq }.ifEmpty { listOf(policy.selectableMaxFreq) }
    val displaySelectedValue = policy.clampToWritableMax(selectedValue)
    val currentIndex = supported.indexOf(displaySelectedValue).takeIf { it >= 0 } ?: supported.lastIndex
    TunerFrequencyCard(
        title = "Cluster ${policy.id}",
        actualLabel = formatFrequency(
            actualValue,
            boosted = policy.isBoosted(actualValue),
            policy = policy,
            displayAsPercent = displayFrequenciesAsPercent,
            showStockLabel = false,
        ),
        selectedLabel = formatFrequency(
            displaySelectedValue,
            boosted = false,
            policy = policy,
            displayAsPercent = displayFrequenciesAsPercent,
        ),
        valueIndex = currentIndex,
        maxIndex = supported.lastIndex,
        onIndexChange = { index -> onValueChanged(supported[index]) },
        compactMode = compactMode,
        focusRequester = focusRequester,
    )
}

@Composable
private fun TunerFrequencyCard(
    title: String,
    actualLabel: String,
    selectedLabel: String,
    valueIndex: Int,
    maxIndex: Int,
    onIndexChange: (Int) -> Unit,
    compactMode: Boolean,
    focusRequester: FocusRequester? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val stock = valueIndex >= maxIndex
    val sliderColor = if (stock) colorScheme.onSurfaceVariant.copy(alpha = 0.58f) else colorScheme.primary

    // Controller support, via the shared hover-then-adjust contract (CtAdjustable)
    // so this card, the edge-handle sliders and the numeric fields cannot drift
    // apart again. A/Center enters adjust mode and grows the card, left/right
    // step, up/down are swallowed while adjusting so focus cannot escape
    // mid-edit, and B leaves adjust mode rather than closing the screen.
    val interactionSource = remember { MutableInteractionSource() }
    val adjustable = rememberCtAdjustable()

    fun step(delta: Int) {
        if (maxIndex <= 0) return
        val next = (valueIndex + delta).coerceIn(0, maxIndex)
        if (next != valueIndex) onIndexChange(next)
    }

    val borderColor = when {
        adjustable.adjusting -> colorScheme.primary
        adjustable.focused -> colorScheme.primary.copy(alpha = 0.82f)
        else -> colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val borderWidth = if (adjustable.focused || adjustable.adjusting) 2.dp else 1.dp
    val scale = adjustable.animatedScale()

    CtRowSurface(
        modifier = Modifier
            .scale(scale)
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(20.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .ctAdjustable(adjustable, interactionSource, onStep = ::step),
        minimumHeight = 62.dp,
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides if (compactMode) Dp.Unspecified else 48.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val tightRow = maxWidth < 420.dp
                val veryTightRow = maxWidth < 340.dp
                val horizontalGap = when {
                    veryTightRow -> 4.dp
                    tightRow -> 5.dp
                    else -> 6.dp
                }
                val labelColumnWidth = when {
                    veryTightRow -> 72.dp
                    tightRow -> 84.dp
                    else -> 96.dp
                }
                val valueColumnWidth = when {
                    veryTightRow -> 58.dp
                    tightRow -> 66.dp
                    else -> 76.dp
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 62.dp)
                        .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(horizontalGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.width(labelColumnWidth),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        androidx.compose.material3.Text(
                            text = "Now $actualLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TunerFrequencySlider(
                        active = adjustable.adjusting,
                        valueIndex = valueIndex,
                        maxIndex = maxIndex,
                        onIndexChange = onIndexChange,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Text(
                        text = selectedLabel,
                        modifier = Modifier.width(valueColumnWidth),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = sliderColor,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun TunerFrequencySlider(
    valueIndex: Int,
    maxIndex: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** True while the parent card is in controller adjust mode. */
    active: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val sliderColor = if (valueIndex >= maxIndex) colorScheme.onSurfaceVariant.copy(alpha = 0.58f) else colorScheme.primary
    val density = LocalDensity.current
    val trackHeight = with(density) { 4.dp.toPx() }
    val tickRadius = with(density) { 1.4.dp.toPx() }
    // Grows while the parent card is in adjust mode, so it is obvious which
    // slider the D-pad is driving without moving the row itself.
    val thumbRadiusDp by animateDpAsState(if (active) 9.dp else 7.dp, label = "tunerThumb")
    val thumbRadius = with(density) { thumbRadiusDp.toPx() }
    val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)

    BoxWithConstraints(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics {
                val rangeMax = maxIndex.coerceAtLeast(0).toFloat()
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = valueIndex.coerceIn(0, maxIndex.coerceAtLeast(0)).toFloat(),
                    range = 0f..rangeMax,
                    steps = (maxIndex - 1).coerceAtLeast(0),
                )
                setProgress { targetValue ->
                    onIndexChange(targetValue.roundToInt().coerceIn(0, maxIndex.coerceAtLeast(0)))
                    true
                }
            }
            .pointerInput(maxIndex) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    fun indexAt(x: Float): Int = if (maxIndex <= 0) {
                        0
                    } else {
                        ((x / width) * maxIndex).roundToInt().coerceIn(0, maxIndex)
                    }
                    onIndexChange(indexAt(down.position.x))
                    val pointerId = down.id
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change != null) {
                            onIndexChange(indexAt(change.position.x))
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .align(Alignment.Center),
        ) {
            val centerY = size.height / 2f
            val clampedMax = maxIndex.coerceAtLeast(1)
            val progress = valueIndex.coerceIn(0, clampedMax).toFloat() / clampedMax.toFloat()
            val thumbX = size.width * progress
            drawRoundRect(
                color = colorScheme.outlineVariant.copy(alpha = 0.34f),
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(size.width, trackHeight),
                cornerRadius = cornerRadius,
            )
            drawRoundRect(
                color = sliderColor,
                topLeft = Offset(0f, centerY - trackHeight / 2f),
                size = Size(thumbX, trackHeight),
                cornerRadius = cornerRadius,
            )
            if (maxIndex > 1) {
                for (index in 0..maxIndex) {
                    val tickX = size.width * (index.toFloat() / maxIndex.toFloat())
                    drawCircle(
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                        radius = tickRadius,
                        center = Offset(tickX, centerY),
                    )
                }
            }
            drawCircle(color = sliderColor, radius = thumbRadius, center = Offset(thumbX, centerY))
        }
    }
}

@Composable
internal fun TunerGpuPolicyCard(
    policy: GpuPolicyInfo,
    selectedValue: Int,
    actualValue: Int = selectedValue,
    onValueChanged: (Int) -> Unit,
    compactMode: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val supported = policy.supportedFrequenciesHz.filter { it <= policy.selectableMaxFrequencyHz }
        .ifEmpty { listOf(policy.selectableMaxFrequencyHz) }.distinct().sorted()
    val selected = selectedValue.coerceIn(supported.first(), supported.last())
    val currentIndex = supported.indexOf(selected).takeIf { it >= 0 }
        ?: supported.indexOfLast { it <= selected }.coerceAtLeast(0)
    TunerFrequencyCard(
        title = "GPU",
        actualLabel = formatGpuFrequency(actualValue),
        selectedLabel = formatGpuFrequency(selected, policy),
        valueIndex = currentIndex,
        maxIndex = supported.lastIndex.coerceAtLeast(0),
        onIndexChange = { index ->
            onValueChanged(supported[index.coerceIn(0, supported.lastIndex)])
        },
        compactMode = compactMode,
        focusRequester = focusRequester,
    )
}

internal fun formatGpuFrequency(valueHz: Int, policy: GpuPolicyInfo? = null): String {
    if (policy != null && valueHz >= policy.selectableMaxFrequencyHz) return "Stock"
    val mhz = valueHz / 1_000_000f
    return if (mhz >= 1000f) "${"%.1f".format(mhz / 1000f)} GHz" else "${mhz.roundToInt()} MHz"
}
