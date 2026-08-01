package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CtPreferenceRow(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    description: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    minimumHeight: Dp = 48.dp,
    horizontalContentSpacing: Dp = 12.dp,
    textSpacing: Dp = 3.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minimumHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .then(if (onClick != null) Modifier.semantics(mergeDescendants = true) {} else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.width(horizontalContentSpacing))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(textSpacing)) {
            title()
            description?.invoke()
        }
        trailing?.let {
            Spacer(Modifier.width(horizontalContentSpacing))
            it()
        }
    }
}

@Composable
internal fun CtSwitchPreference(
    title: @Composable () -> Unit,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    minimumHeight: Dp = 48.dp,
    horizontalContentSpacing: Dp = 12.dp,
    textSpacing: Dp = 3.dp,
) {
    CtPreferenceRow(
        title = title,
        description = description,
        leading = leading,
        enabled = enabled,
        minimumHeight = minimumHeight,
        horizontalContentSpacing = horizontalContentSpacing,
        textSpacing = textSpacing,
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics(mergeDescendants = true) {},
        trailing = {
            CtSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
}
