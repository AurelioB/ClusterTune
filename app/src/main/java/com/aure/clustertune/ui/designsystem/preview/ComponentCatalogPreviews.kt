package com.aure.clustertune.ui.designsystem.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aure.clustertune.ui.designsystem.component.CtAppIdentity
import com.aure.clustertune.ui.designsystem.component.CtConfirmationDialog
import com.aure.clustertune.ui.designsystem.component.CtIcon
import com.aure.clustertune.ui.designsystem.component.CtDashedCard
import com.aure.clustertune.ui.designsystem.component.CtDivider
import com.aure.clustertune.ui.designsystem.component.CtModalScaffold
import com.aure.clustertune.ui.designsystem.component.CtNumericField
import com.aure.clustertune.ui.designsystem.component.CtOverlayFrame
import com.aure.clustertune.ui.designsystem.component.CtPreferenceRow
import com.aure.clustertune.ui.designsystem.component.CtSwitchPreference
import com.aure.clustertune.ui.designsystem.component.CtSwitch
import com.aure.clustertune.ui.designsystem.component.CtRowSurface
import com.aure.clustertune.ui.designsystem.component.CtSectionCard
import com.aure.clustertune.ui.designsystem.component.CtSelectableRow
import com.aure.clustertune.ui.designsystem.component.CtSelectionIndicator
import com.aure.clustertune.ui.designsystem.component.CtSlider
import com.aure.clustertune.ui.designsystem.component.CtStatePanel
import com.aure.clustertune.ui.designsystem.component.CtStatePanelState

/** A compact catalog of stable light/dark states for visual inspection in Android Studio. */
@Preview(name = "Components • light", widthDp = 420, heightDp = 760, showBackground = true)
@Composable
internal fun ComponentCatalogLightPreview() {
    ClusterTuneLightPreview { ComponentCatalogContent() }
}

@Preview(name = "Components • dark", widthDp = 420, heightDp = 760, showBackground = true)
@Composable
internal fun ComponentCatalogDarkPreview() {
    ClusterTuneDarkPreview { ComponentCatalogContent() }
}

@Preview(name = "Overlay • light", widthDp = 900, heightDp = 520, showBackground = true)
@Composable
internal fun ComponentCatalogOverlayPreview() {
    ClusterTuneLightPreview {
        CtOverlayFrame(onDismissRequest = {}) {
            CtModalScaffold(
                title = { Text("Select profile") },
                footer = { Button(onClick = {}) { Text("Done") } },
            ) {
                Text("Choose how this app should run.")
                CtSelectableRow(title = { Text("Balanced") }, selected = true, onClick = {})
                CtSelectableRow(title = { Text("Performance") }, selected = false, onClick = {})
            }
        }
    }
}

@Preview(name = "Confirmation • dark", widthDp = 420, heightDp = 360, showBackground = true)
@Composable
internal fun ComponentCatalogConfirmationPreview() {
    ClusterTuneDarkPreview {
        CtConfirmationDialog(
            title = "Delete profile",
            message = "This cannot be undone.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            onConfirm = {},
            onDismissRequest = {},
            destructive = true,
        )
    }
}

@Composable
private fun ComponentCatalogContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CtAppIdentity(
            label = ComponentFixtures.rowTitle,
            subtitle = ComponentFixtures.supportingText,
            icon = { CtIcon(Icons.Outlined.Check, contentDescription = null) },
            compact = true,
        )
        CtSectionCard(title = { Text("Controls") }) {
            var slider by remember { mutableStateOf(0.65f) }
            var directSwitch by remember { mutableStateOf(true) }
            CtPreferenceRow(
                title = { Text("Enabled") },
                description = { Text(ComponentFixtures.supportingText) },
                trailing = { CtSelectionIndicator(selected = true) },
            )
            CtSwitchPreference(
                title = { Text("Apply automatically") },
                checked = true,
                onCheckedChange = {},
            )
            CtSwitch(
                checked = directSwitch,
                onCheckedChange = { directSwitch = it },
            )
            CtSlider(value = slider, onValueChange = { slider = it })
            CtNumericField(value = "1200", onValueChange = {})
        }
        CtRowSurface(selected = true, onClick = {}) {
            Text(ComponentFixtures.value, modifier = Modifier.weight(1f))
            CtIcon(Icons.Outlined.Edit, contentDescription = null)
        }
        CtDashedCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("Add profile") }
        }
        CtStatePanel(
            state = CtStatePanelState.Loading,
            title = { Text("Loading") },
            message = { Text("Reading device state") },
        )
        CtStatePanel(
            state = CtStatePanelState.Warning,
            title = { Text("Needs attention") },
            message = { Text("Permission is not enabled") },
        )
        CtDivider()
    }
}
