package com.aure.clustertune.ui.designsystem.token

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Window thresholds intentionally expressed as Dp so the policy is JVM-testable. */
internal object ClusterTuneBreakpoints {
    /** Short landscape windows at or below this height use compact settings rhythm. */
    val compactLandscapeMaxHeight: Dp = 600.dp
    /** Prevents narrow portrait windows from being classified as landscape compact. */
    val compactLandscapeMinWidth: Dp = 600.dp
    /** Two settings columns remain usable down to roughly 340 dp per column. */
    val twoColumnSettingsMinWidth: Dp = 720.dp

    fun isCompactLandscape(width: Dp, height: Dp): Boolean =
        width >= compactLandscapeMinWidth &&
            width > height &&
            height <= compactLandscapeMaxHeight

    fun usesTwoColumnSettings(width: Dp, height: Dp): Boolean =
        isCompactLandscape(width, height) && width >= twoColumnSettingsMinWidth

    fun spacingFor(width: Dp, height: Dp): ClusterTuneSpacing =
        if (isCompactLandscape(width, height)) {
            ClusterTuneSpacings.CompactLandscape
        } else {
            ClusterTuneSpacings.Regular
        }

    fun sizingFor(width: Dp, height: Dp): ClusterTuneSizing =
        if (isCompactLandscape(width, height)) {
            ClusterTuneSizes.CompactLandscape
        } else {
            ClusterTuneSizes.Regular
        }

    fun densityFor(width: Dp, height: Dp): ClusterTuneDensity =
        if (isCompactLandscape(width, height)) {
            ClusterTuneDensities.CompactLandscape
        } else {
            ClusterTuneDensities.Regular
        }
}
