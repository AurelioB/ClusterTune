package com.aure.clustertune.ui.designsystem.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic spacing values shared by ClusterTune surfaces. */
internal data class ClusterTuneSpacing(
    val screenHorizontal: Dp,
    val screenVertical: Dp,
    val sectionGap: Dp,
    val cardPadding: Dp,
    val contentGap: Dp,
)

internal object ClusterTuneSpacings {
    /** Existing screen rhythm, kept as the default for the main profiles view. */
    val Regular = ClusterTuneSpacing(
        screenHorizontal = 20.dp,
        screenVertical = 28.dp,
        sectionGap = 18.dp,
        cardPadding = 16.dp,
        contentGap = 12.dp,
    )

    /** Reduced vertical rhythm for short landscape settings windows. */
    val CompactLandscape = ClusterTuneSpacing(
        screenHorizontal = 20.dp,
        screenVertical = 16.dp,
        sectionGap = 12.dp,
        cardPadding = 12.dp,
        contentGap = 8.dp,
    )
}
