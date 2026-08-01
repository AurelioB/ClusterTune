package com.aure.clustertune.ui.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colors owned by ClusterTune. Values intentionally alias Material 3 roles. */
@Immutable
internal data class ClusterTuneColors(
    val rowSurface: Color,
    val selectedRowSurface: Color,
    val subtleBorder: Color,
    val divider: Color,
    val mutedContent: Color,
    val modalSurface: Color,
    val overlayPanel: Color,
    val overlayScrim: Color,
) {
    companion object {
        internal fun from(scheme: ColorScheme): ClusterTuneColors = ClusterTuneColors(
            rowSurface = scheme.surface,
            selectedRowSurface = scheme.secondaryContainer,
            subtleBorder = scheme.outlineVariant,
            divider = scheme.outlineVariant,
            mutedContent = scheme.onSurfaceVariant,
            modalSurface = scheme.surfaceContainerHigh,
            overlayPanel = scheme.surface,
            overlayScrim = scheme.scrim.copy(alpha = 0.32f),
        )
    }
}

internal val LocalClusterTuneColors = staticCompositionLocalOf { ClusterTuneColors.from(ColorSchemeDefaults) }

/** Current semantic palette, derived from the active Material color scheme. */
internal val MaterialTheme.clusterTuneColors: ClusterTuneColors
    @Composable get() = LocalClusterTuneColors.current

private val ColorSchemeDefaults: ColorScheme
    get() = androidx.compose.material3.lightColorScheme()
