package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A single-choice row with one merged radio-button action. */
@Composable
internal fun CtSelectableRow(
    title: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
) {
    CtRowSurface(
        modifier = modifier.selectable(selected, enabled, Role.RadioButton, onClick),
        selected = selected,
        enabled = enabled,
        content = {
            leading?.invoke()
            if (leading != null) Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                title()
                description?.invoke()
            }
            trailing?.invoke(this)
            CtSelectionIndicator(selected = selected, enabled = enabled)
        },
    )
}
