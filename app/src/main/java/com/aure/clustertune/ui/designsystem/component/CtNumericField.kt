package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValueChange(numericDigits(next, maxDigits)) },
        modifier = modifier.height(containerHeight.coerceAtLeast(48.dp)),
        label = label,
        supportingText = supportingText,
        isError = isError,
        enabled = enabled,
        readOnly = readOnly,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        minLines = 1,
    )
}

internal fun numericDigits(value: String, maxDigits: Int): String =
    value.filter(Char::isDigit).take(maxDigits.coerceAtLeast(0))
