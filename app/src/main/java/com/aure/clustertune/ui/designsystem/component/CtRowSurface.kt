package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared row-like surface with state-aware Material colors and caller-owned slots. */
@Composable
internal fun CtRowSurface(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    minimumHeight: Dp = 48.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minimumHeight)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = onClick != null) { if (selected) this.selected = true },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) scheme.primaryContainer.copy(alpha = 0.24f) else scheme.surfaceContainerHigh.copy(alpha = 0.46f),
        contentColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, if (selected) scheme.primary.copy(alpha = 0.82f) else scheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
