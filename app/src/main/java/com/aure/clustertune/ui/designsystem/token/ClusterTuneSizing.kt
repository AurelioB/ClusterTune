package com.aure.clustertune.ui.designsystem.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic dimensions; visual compactness never reduces the touch target. */
internal data class ClusterTuneSizing(
    val interactiveTarget: Dp,
    val appearanceControlHeight: Dp,
    val accentSwatchSize: Dp,
    val numericFieldHeight: Dp,
    val sectionIconContainerSize: Dp,
    val sectionIconSize: Dp,
    val dividerThickness: Dp,
)

internal object ClusterTuneSizes {
    /** Baseline dimensions used by the existing regular UI. */
    val Regular = ClusterTuneSizing(
        interactiveTarget = 48.dp,
        appearanceControlHeight = 48.dp,
        accentSwatchSize = 42.dp,
        numericFieldHeight = 56.dp,
        sectionIconContainerSize = 36.dp,
        sectionIconSize = 20.dp,
        dividerThickness = 1.dp,
    )

    /** Compact visuals for short windows, retaining the 48 dp interaction target. */
    val CompactLandscape = ClusterTuneSizing(
        interactiveTarget = 48.dp,
        appearanceControlHeight = 36.dp,
        accentSwatchSize = 32.dp,
        numericFieldHeight = 48.dp,
        sectionIconContainerSize = 32.dp,
        sectionIconSize = 18.dp,
        dividerThickness = 1.dp,
    )
}
