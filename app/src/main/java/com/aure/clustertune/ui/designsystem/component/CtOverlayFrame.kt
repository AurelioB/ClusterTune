package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object CtOverlayFrameTestTags {
    const val Scrim = "CtOverlayFrame.scrim"
    const val Panel = "CtOverlayFrame.panel"
}

/** A layered overlay: independently composited scrim and opaque, input-isolated panel. */
@Composable
internal fun CtOverlayFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    panelModifier: Modifier = Modifier,
    dismissOnClickOutside: Boolean = true,
    scrimColor: Color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f),
    panelColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    maxWidth: Dp = 900.dp,
    maxHeight: Dp = Dp.Infinity,
    widthFraction: Float = 0.86f,
    heightFraction: Float? = null,
    panelShape: Shape = RoundedCornerShape(28.dp),
    panelTonalElevation: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Keep this as a separate layer. Applying alpha to the parent would also fade the panel.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(CtOverlayFrameTestTags.Scrim)
                .background(scrimColor)
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.pointerInput(onDismissRequest) {
                            detectTapGestures { onDismissRequest() }
                        }
                    } else Modifier,
                ),
        )
        val resolvedWidth = minOf(
            maxWidth,
            this.maxWidth * widthFraction.coerceIn(0f, 1f),
        )
        val resolvedHeight = heightFraction?.let {
            minOf(maxHeight, this.maxHeight * it.coerceIn(0f, 1f))
        }
        Surface(
            modifier = Modifier
                .width(resolvedWidth)
                .then(
                    resolvedHeight?.let { Modifier.height(it) }
                        ?: Modifier.wrapContentHeight(),
                )
                .widthIn(max = maxWidth)
                .then(
                    if (resolvedHeight == null && maxHeight != Dp.Infinity) {
                        Modifier.heightIn(max = maxHeight)
                    } else {
                        Modifier
                    },
                )
                .then(panelModifier)
                .testTag(CtOverlayFrameTestTags.Panel)
                // Consume panel taps so they cannot reach the scrim layer.
                .pointerInput(Unit) { detectTapGestures { } },
            shape = panelShape,
            color = panelColor,
            tonalElevation = panelTonalElevation,
        ) {
            content()
        }
    }
}

/** The compact profile picker frame shared by the in-app and edge overlays. */
@Composable
internal fun CtCompactOverlayFrame(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    CtOverlayFrame(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f),
        maxWidth = 520.dp,
        maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.92f,
        widthFraction = 1f,
        heightFraction = null,
        panelShape = RoundedCornerShape(20.dp),
        panelColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        panelTonalElevation = 0.dp,
        panelModifier = Modifier.padding(horizontal = 12.dp),
        content = content,
    )
}
