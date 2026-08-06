package com.aure.clustertune.ui.designsystem.token

/** The complete set of layout tokens selected for a window. */
internal data class ClusterTuneDensity(
    val spacing: ClusterTuneSpacing,
    val sizing: ClusterTuneSizing,
)

internal object ClusterTuneDensities {
    val Regular = ClusterTuneDensity(
        spacing = ClusterTuneSpacings.Regular,
        sizing = ClusterTuneSizes.Regular,
    )

    val CompactLandscape = ClusterTuneDensity(
        spacing = ClusterTuneSpacings.CompactLandscape,
        sizing = ClusterTuneSizes.CompactLandscape,
    )
}
