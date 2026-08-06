package com.aure.clustertune.ui.designsystem.component

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The low-contrast divider used between sections and modal content. */
@Composable
internal fun CtDivider(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f),
    thickness: Dp = 1.dp,
) {
    HorizontalDivider(modifier = modifier, color = color, thickness = thickness)
}
