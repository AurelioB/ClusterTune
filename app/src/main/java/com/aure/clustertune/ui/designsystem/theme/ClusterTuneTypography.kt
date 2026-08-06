package com.aure.clustertune.ui.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle

@Immutable
internal data class ClusterTuneTypography(
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val rowTitle: TextStyle,
    val supportingText: TextStyle,
    val metadata: TextStyle,
    val value: TextStyle,
    val controlLabel: TextStyle,
) {
    companion object {
        internal fun from(t: Typography) = ClusterTuneTypography(
            screenTitle = t.headlineSmall,
            sectionTitle = t.titleMedium,
            rowTitle = t.bodyLarge,
            supportingText = t.bodyMedium,
            metadata = t.labelSmall,
            value = t.titleMedium,
            controlLabel = t.labelLarge,
        )
    }
}

internal val MaterialTheme.clusterTuneTypography: ClusterTuneTypography
    @Composable
    get() = ClusterTuneTypography.from(typography)
