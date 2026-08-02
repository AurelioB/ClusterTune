package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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

/** A compact single-line numeric field; callers own validation and localized labels. */
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
    CtCompactOutlinedField(
        value = value,
        onValueChange = { next -> onValueChange(numericDigits(next, maxDigits)) },
        modifier = modifier,
        label = label,
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        readOnly = readOnly,
        containerHeight = containerHeight,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

internal fun numericDigits(value: String, maxDigits: Int): String =
    value.filter(Char::isDigit).take(maxDigits.coerceAtLeast(0))
