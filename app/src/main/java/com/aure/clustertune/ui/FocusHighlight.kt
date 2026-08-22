package com.aure.clustertune.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Adds a clearly visible focus indication (beige primary border + slight scale)
 * when a component gains D-pad/controller focus. The rows/buttons this is applied
 * to sit on dark surfaces, so the app's beige [primary] accent reads clearly as a
 * border while still matching the rest of the UI. Pass [highlightColor] to
 * override; pass null to use the theme primary.
 *
 * Pass [focusRequester] to make this element an initial-focus target (call
 * requestFocus() on it when the screen/dialog opens).
 */
fun Modifier.focusHighlight(
    highlightColor: Color? = null,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    borderWidth: Dp = 3.dp,
    focusedScale: Float = 1.03f,
    focusRequester: FocusRequester? = null,
): Modifier = composed {
    val resolvedColor = highlightColor ?: MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    this
        .scale(scale)
        .border(
            width = if (focused) borderWidth else 0.dp,
            color = if (focused) resolvedColor else Color.Transparent,
            shape = shape,
        )
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
    // NOTE: no .focusable() here — the element this is applied to is expected to
    // be clickable (rows) or a Button, which already provides a single focus
    // target. Adding focusable() would create a duplicate target and require
    // two D-pad presses per item.
}
