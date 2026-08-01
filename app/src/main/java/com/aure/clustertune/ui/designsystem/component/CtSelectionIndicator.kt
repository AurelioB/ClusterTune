package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape

/** A compact circular selected/unselected indicator with an optional 48 dp target. */
@Composable
internal fun CtSelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    size: Dp = 26.dp,
    contentDescription: String? = null,
) {
    val target = Modifier.size(48.dp).then(modifier)
    Box(
        modifier = (if (onClick == null) {
            target
        } else {
            target.selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
        }).then(
            if (contentDescription == null) Modifier
            else Modifier.semantics { this.contentDescription = contentDescription }
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            border = BorderStroke(
                2.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ),
        ) {
            if (selected) {
                CtIcon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(size * 0.65f))
            }
        }
    }
}
