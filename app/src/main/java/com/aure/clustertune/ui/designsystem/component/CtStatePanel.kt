package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

internal enum class CtStatePanelState { Empty, Loading, Warning, Error }

/** Shared empty/loading/warning/error surface. Product copy remains caller-owned. */
@Composable
internal fun CtStatePanel(
    state: CtStatePanelState,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    message: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    containerColor: Color? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(12.dp),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor ?: when (state) {
            CtStatePanelState.Loading -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
            CtStatePanelState.Error -> MaterialTheme.colorScheme.errorContainer
            CtStatePanelState.Warning -> MaterialTheme.colorScheme.tertiaryContainer
            CtStatePanelState.Empty -> MaterialTheme.colorScheme.surfaceContainer
        },
        shape = shape,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(contentPadding),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == CtStatePanelState.Loading && leading == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                )
            } else {
                leading?.invoke()
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                title()
                message?.invoke()
            }
            action?.invoke()
        }
    }
}
