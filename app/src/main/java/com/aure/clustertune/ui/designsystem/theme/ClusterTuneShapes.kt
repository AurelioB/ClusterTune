package com.aure.clustertune.ui.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
internal data class ClusterTuneShapes(
    val iconTile: Shape,
    val field: Shape,
    val row: Shape,
    val section: Shape,
    val modal: Shape,
    val full: Shape,
    val pill: Shape,
) {
    companion object {
        internal fun from(s: Shapes) = ClusterTuneShapes(
            iconTile = s.small,
            field = s.small,
            row = s.medium,
            section = s.large,
            modal = s.extraLarge,
            full = RoundedCornerShape(0.dp),
            pill = androidx.compose.foundation.shape.CircleShape,
        )
    }
}

internal val MaterialTheme.clusterTuneShapes: ClusterTuneShapes
    @Composable
    get() = ClusterTuneShapes.from(shapes)
