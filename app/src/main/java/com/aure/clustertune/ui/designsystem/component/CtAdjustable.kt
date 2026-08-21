package com.aure.clustertune.ui.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Hover-then-adjust state for a control driven by a game controller.
 *
 * One implementation, shared by every slider-like control, because keeping
 * separate copies is exactly how they drifted: the CPU cards, the edge-handle
 * sliders and the numeric fields each ended up with a different subset of the
 * behaviour, and each round of "copy the working one across" copied a bit more
 * of it but never all.
 *
 * The full contract:
 *  - D-pad moves focus between controls; the control draws a hover outline.
 *  - A / Center / Enter enters **adjust mode**, which grows the control so it is
 *    obvious the next presses will change a value rather than move focus.
 *  - While adjusting, left/right step the value and **up/down are swallowed**,
 *    so focus cannot escape mid-edit.
 *  - While adjusting, B / Back leaves adjust mode only. It must not fall through
 *    to the screen's back handling, which previously threw the user out to the
 *    main screen from the middle of a settings slider.
 *  - Losing focus by any other route silently leaves adjust mode.
 */
internal class CtAdjustableState {
    var focused by mutableStateOf(false)
        internal set
    var adjusting by mutableStateOf(false)
        internal set
}

/**
 * Creates [CtAdjustableState] and installs the back handling that lets B exit
 * adjust mode.
 *
 * The dispatcher owner is checked because overlay windows are composed by
 * `OverlayComposeViewFactory`, which never installs one; calling [BackHandler]
 * there crashes. In overlays the key handler still consumes B directly, so the
 * behaviour is the same either way.
 */
@Composable
internal fun rememberCtAdjustable(): CtAdjustableState {
    val state = remember { CtAdjustableState() }
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(enabled = state.adjusting) { state.adjusting = false }
    }
    return state
}

/**
 * Container scale: 1.02 on **hover only**.
 *
 * Entering adjust mode deliberately does not resize the container. The 1.0.2
 * build signalled adjust mode by growing the slider **knob** (7dp -> 9dp) while
 * the box stayed put, which reads as "this control is now being driven" rather
 * than the whole row jumping. Growing the box on A was my invention and it was
 * wrong; see [adjustingThumbRadius].
 */
@Composable
internal fun CtAdjustableState.animatedScale(): Float =
    animateFloatAsState(if (focused) 1.02f else 1f, label = "ctAdjustableScale").value

/** Knob radius for the adjust-mode feedback: 7dp at rest, 9dp while adjusting. */
@Composable
internal fun CtAdjustableState.thumbRadiusDp(
    rest: Dp = 7.dp,
    active: Dp = 9.dp,
): Dp = animateDpAsState(if (adjusting) active else rest, label = "ctAdjustableThumb").value

/**
 * Applies focus tracking, the key contract above, and focusability.
 *
 * `focusable` is what actually makes the control a focus target — observing
 * focus with [onFocusChanged] alone does not, which is why an earlier attempt at
 * this looked right and did nothing.
 *
 * [onStep] receives -1 or +1 and is only called while adjusting.
 */
internal fun Modifier.ctAdjustable(
    state: CtAdjustableState,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    onStep: (Int) -> Unit,
): Modifier = this
    .onFocusChanged {
        state.focused = it.isFocused
        if (!it.isFocused) state.adjusting = false
    }
    .onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        if (event.type != KeyEventType.KeyDown) {
            // Swallow the matching key-up too, so the release of a consumed
            // press cannot reach anything behind this control.
            return@onPreviewKeyEvent state.adjusting && event.key in ADJUST_KEYS
        }
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA -> {
                state.adjusting = !state.adjusting
                true
            }
            Key.Back, Key.ButtonB -> if (state.adjusting) {
                state.adjusting = false
                true
            } else {
                false
            }
            Key.DirectionLeft -> if (state.adjusting) { onStep(-1); true } else false
            Key.DirectionRight -> if (state.adjusting) { onStep(1); true } else false
            // Consumed while adjusting so vertical focus search cannot run.
            Key.DirectionUp, Key.DirectionDown -> state.adjusting
            else -> false
        }
    }
    .focusable(enabled = enabled, interactionSource = interactionSource)

private val ADJUST_KEYS = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.ButtonA,
    Key.Back, Key.ButtonB, Key.DirectionLeft, Key.DirectionRight,
    Key.DirectionUp, Key.DirectionDown,
)
