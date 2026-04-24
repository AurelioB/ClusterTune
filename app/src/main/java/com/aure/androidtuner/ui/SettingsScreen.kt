package com.aure.androidtuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aure.androidtuner.model.AppSettings
import com.aure.androidtuner.model.TileInteractionBehavior

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onTileTapBehaviorChange: (TileInteractionBehavior) -> Unit,
    onTileLongPressBehaviorChange: (TileInteractionBehavior) -> Unit,
    onApplyLastPresetOnBootChange: (Boolean) -> Unit,
    onResetProfiles: () -> Unit,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Quick settings tile behavior and preset defaults.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            TextButton(onClick = onBack) {
                Text("Done")
            }
        }

        SettingsSection(title = "Single Tap On Tile") {
            TileBehaviorSelector(
                selected = settings.tileTapBehavior,
                onChange = onTileTapBehaviorChange,
            )
        }

        SettingsSection(title = "Long Press On Tile") {
            TileBehaviorSelector(
                selected = settings.tileLongPressBehavior,
                onChange = onTileLongPressBehaviorChange,
            )
        }

        SettingsSection(title = "Startup") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Apply last preset on device boot",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When enabled, the app will attempt to restore the last applied preset after boot.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.applyLastPresetOnBoot,
                    onCheckedChange = onApplyLastPresetOnBootChange,
                )
            }
        }

        SettingsSection(title = "Presets") {
            Text(
                text = "Reset presets to default",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Bundled presets are restored and custom presets are removed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = { showResetConfirmation = true },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Reset")
            }
        }
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset presets?") },
            text = {
                Text("This removes custom presets and restores the bundled defaults.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        onResetProfiles()
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun TileBehaviorSelector(
    selected: TileInteractionBehavior,
    onChange: (TileInteractionBehavior) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TileBehaviorOption(
            title = "Show dialog",
            description = "Open the compact editor dialog.",
            selected = selected == TileInteractionBehavior.SHOW_DIALOG,
            onClick = { onChange(TileInteractionBehavior.SHOW_DIALOG) },
        )
        TileBehaviorOption(
            title = "Cycle presets",
            description = "Apply the next preset immediately.",
            selected = selected == TileInteractionBehavior.CYCLE_PRESETS,
            onClick = { onChange(TileInteractionBehavior.CYCLE_PRESETS) },
        )
    }
}

@Composable
private fun TileBehaviorOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        },
    )
}
