package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared inset for compact outlined controls. Keeps labels and values clear at reduced heights. */
internal object CtCompactFieldDefaults {
    val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CtCompactOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    containerHeight: Dp = 56.dp,
    contentPadding: PaddingValues = CtCompactFieldDefaults.contentPadding,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = OutlinedTextFieldDefaults.shape
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(containerHeight.coerceAtLeast(48.dp)),
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        textStyle = textStyle.merge(TextStyle(color = textColor)),
        keyboardOptions = keyboardOptions,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = isError,
                label = label,
                trailingIcon = trailingIcon,
                supportingText = supportingText,
                colors = colors,
                contentPadding = contentPadding,
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interactionSource,
                        colors = colors,
                        shape = shape,
                    )
                },
            )
        },
    )
}

/**
 * A compact single-line numeric field; callers own validation and localized labels.
 *
 * Controller behaviour: when reached by D-pad the field starts in a non-editing
 * "hover" state — focusable and outlined, but it does not take text focus, so the
 * soft keyboard stays closed and you can continue past it with up/down. Pressing
 * A/Center (or tapping) enters edit mode, which takes text focus and opens the
 * keyboard; Back/B or Enter commits and returns to hover. Without this, simply
 * moving focus over a numeric field pops the keyboard and traps navigation.
 */
@Composable
internal fun CtNumericField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    containerHeight: Dp = 56.dp,
    maxDigits: Int = Int.MAX_VALUE,
) {
    var editing by remember { mutableStateOf(false) }
    var hoverFocused by remember { mutableStateOf(false) }
    val editFocus = remember { FocusRequester() }

    if (editing && enabled && !readOnly) {
        var everFocused by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { runCatching { editFocus.requestFocus() } }
        CtCompactOutlinedField(
            value = value,
            onValueChange = { next -> onValueChange(numericDigits(next, maxDigits)) },
            modifier = modifier
                .focusRequester(editFocus)
                .onFocusChanged {
                    if (it.isFocused) {
                        everFocused = true
                    } else if (everFocused) {
                        // Only exit once focus was genuinely acquired then lost,
                        // not on the frame before requestFocus() resolves.
                        editing = false
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.ButtonB ||
                            event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        editing = false
                        true
                    } else {
                        false
                    }
                },
            label = label,
            supportingText = supportingText,
            isError = isError,
            enabled = enabled,
            readOnly = readOnly,
            containerHeight = containerHeight,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    } else {
        // Hover state: same outlined container and still accepts value changes
        // (so programmatic/accessibility text input and instrumented tests behave
        // as before). The difference is purely that it does not request text
        // focus, so moving D-pad focus here does not open the soft keyboard.
        // A/Center or tap switches to the real editing field.
        val scheme = MaterialTheme.colorScheme
        val borderColor = if (hoverFocused) scheme.primary.copy(alpha = 0.82f) else scheme.outlineVariant
        CtCompactOutlinedField(
            value = value,
            onValueChange = { next -> onValueChange(numericDigits(next, maxDigits)) },
            modifier = modifier
                .onFocusChanged { hoverFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (!enabled || readOnly) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter ||
                            event.key == Key.NumPadEnter || event.key == Key.Spacebar ||
                            event.key == Key.ButtonA)
                    ) {
                        editing = true
                        true
                    } else {
                        false
                    }
                }
                .focusable(enabled = enabled)
                .clickable(enabled = enabled && !readOnly) { editing = true },
            label = label,
            supportingText = supportingText,
            isError = isError,
            enabled = enabled,
            readOnly = readOnly,
            containerHeight = containerHeight,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = borderColor,
                focusedBorderColor = borderColor,
                disabledBorderColor = scheme.outlineVariant.copy(alpha = 0.38f),
            ),
        )
    }
}

internal fun numericDigits(value: String, maxDigits: Int): String =
    value.filter(Char::isDigit).take(maxDigits.coerceAtLeast(0))
