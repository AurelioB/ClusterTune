package com.aure.clustertune.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    val supported = policy.supportedFrequencies
    val displaySelectedValue = policy.clampToWritableMax(selectedValue)
    val currentIndex = supported.indexOf(displaySelectedValue).takeIf { it >= 0 } ?: supported.lastIndex
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    val interactionSource = remember { MutableInteractionSource() }

    // Controller support. The card itself is the focus target (the slider inside
    // is touch-only by construction), so D-pad up/down moves between clusters.
    // Pressing A/Center enters "adjust mode", where left/right step the value;
    // pressing again (or moving focus away) leaves it. Colors follow the same
    // tokens CtRowSurface uses so this matches the rest of the design system.
    var focused by remember { mutableStateOf(false) }
    var adjusting by remember { mutableStateOf(false) }

    fun step(delta: Int) {
        val maxIndex = supported.lastIndex
        if (maxIndex <= 0) return
        val next = (currentIndex + delta).coerceIn(0, maxIndex)
        if (next != currentIndex) onValueChanged(supported[next])
    }

    val containerColor = when {
        adjusting -> colorScheme.primaryContainer.copy(alpha = 0.24f)
        else -> colorScheme.surfaceContainerHigh.copy(alpha = 0.46f)
    }
    val borderColor = when {
        adjusting -> colorScheme.primary
        focused -> colorScheme.primary.copy(alpha = 0.82f)
        else -> colorScheme.outlineVariant.copy(alpha = 0.28f)
    }
    val borderWidth = if (focused || adjusting) 2.dp else 1.dp
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "policyCardScale")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) adjusting = false
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA -> {
                        adjusting = !adjusting
                        true
                    }
                    // Left/right only consumed while adjusting, so otherwise they
                    // still traverse focus. Back/B is never consumed here: it falls
                    // through so a single press closes the surrounding dialog.
                    Key.DirectionLeft -> if (adjusting) { step(-1); true } else false
                    Key.DirectionRight -> if (adjusting) { step(1); true } else false
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource),
        shape = rowShape,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        CompositionLocalProvider(
            LocalMinimumInteractiveComponentSize provides if (compactMode) Dp.Unspecified else 48.dp,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val tightRow = maxWidth < 420.dp
                val veryTightRow = maxWidth < 340.dp
                val horizontalGap = when {
                    veryTightRow -> 4.dp
                    tightRow -> 5.dp
                    else -> 6.dp
                }
                val clusterColumnWidth = when {
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
                        modifier = Modifier.width(clusterColumnWidth),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = "Cluster ${policy.id}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        androidx.compose.material3.Text(
                            text = "Now ${formatFrequency(actualValue, boosted = policy.isBoosted(actualValue), policy = policy, displayAsPercent = displayFrequenciesAsPercent)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    TunerFrequencySlider(
                        valueIndex = currentIndex,
                        maxIndex = supported.lastIndex,
                        onIndexChange = { index -> onValueChanged(supported[index]) },
                        modifier = Modifier.weight(1f),
                        active = adjusting,
                    )

                    androidx.compose.material3.Text(
                        text = formatFrequency(selectedValue, boosted = policy.isBoosted(selectedValue), policy = policy, displayAsPercent = displayFrequenciesAsPercent),
                        modifier = Modifier.width(valueColumnWidth),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.primary,
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
    active: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val trackHeight = with(density) { 4.dp.toPx() }
    val tickRadius = with(density) { 1.4.dp.toPx() }
    // Grows while the parent card is in controller "adjust mode" so it's obvious
    // which slider the D-pad is driving.
    val thumbRadius = with(density) { if (active) 9.dp.toPx() else 7.dp.toPx() }
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
                color = colorScheme.primary,
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
            drawCircle(color = colorScheme.primary, radius = thumbRadius, center = Offset(thumbX, centerY))
        }
    }
}
