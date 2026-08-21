package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** A compact-looking slider that retains Material's 48 dp interaction target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CtSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: SliderColors = SliderDefaults.colors(),
    visualHeight: androidx.compose.ui.unit.Dp = 36.dp,
    trackHeight: androidx.compose.ui.unit.Dp = 6.dp,
    thumbSize: DpSize = DpSize(4.dp, 24.dp),
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange),
                    range = valueRange,
                )
                if (!enabled) disabled()
                setProgress { targetValue ->
                    if (!enabled) {
                        false
                    } else {
                        val coercedValue = targetValue.coerceIn(valueRange)
                        if (coercedValue == value) {
                            false
                        } else {
                            onValueChange(coercedValue)
                            true
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(visualHeight),
            valueRange = valueRange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    colors = colors,
                    thumbSize = thumbSize,
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(trackHeight),
                    colors = colors,
                )
            },
        )
    }
}
