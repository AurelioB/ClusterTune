package com.aure.clustertune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TileInteractionBehavior

private val accentColorOptions = listOf(
    0xFF3F51B5.toInt(),
    0xFF006E1C.toInt(),
    0xFFB3261E.toInt(),
    0xFF8E24AA.toInt(),
    0xFF00639A.toInt(),
    0xFF9A4600.toInt(),
)

private data class ExecutionMethodInfo(
    val id: String,
    val label: String,
    val appliesTo: String,
    val note: String,
)

private val executionMethodInfo = listOf(
    ExecutionMethodInfo(
        id = "pserver-stdout",
        label = "PServer direct",
        appliesTo = "Vendor PServer implementations that return command output.",
        note = "First choice during automatic detection.",
    ),
    ExecutionMethodInfo(
        id = "pserver-file-output",
        label = "PServer compatibility",
        appliesTo = "Vendor PServer implementations without usable command output.",
        note = "Second automatic choice; dispatches writes without requesting output.",
    ),
    ExecutionMethodInfo(
        id = "root-shell",
        label = "Root shell",
        appliesTo = "Generic rooted devices with Magisk/su.",
        note = "Final automatic choice when neither PServer mode is usable.",
    ),
    ExecutionMethodInfo(
        id = "shizuku",
        label = "Shizuku (experimental)",
        appliesTo = "Manual testing on devices with Shizuku or Sui.",
        note = "Not auto-selected; permission alone may not allow CPU control.",
    ),
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onColorSourceChange: (AppColorSource) -> Unit,
    onAccentColorChange: (Int) -> Unit,
    onCustomAccentColorChange: (Int) -> Unit,
    onDisplayFrequenciesAsPercentChange: (Boolean) -> Unit,
    onTileTapBehaviorChange: (TileInteractionBehavior) -> Unit,
    onApplyLastProfileOnBootChange: (Boolean) -> Unit,
    sleepProfileOptions: List<PerformanceProfile>,
    onSleepProfileEnabledChange: (Boolean) -> Unit,
    onSleepProfileChange: (String?) -> Unit,
    onResetProfiles: () -> Unit,
    onExportProfiles: () -> Unit,
    onImportProfiles: () -> Unit,
    onRequestAddQuickSettingsTile: () -> Unit,
    canRequestAddQuickSettingsTile: Boolean,
    isQuickSettingsTileAdded: Boolean,
    canDrawOverlays: Boolean,
    onOpenOverlayPermissionSettings: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onAutomaticUpdateChecksEnabledChange: (Boolean) -> Unit,
    onUpdateCheckIntervalDaysChange: (Int) -> Unit,
    onIncludePrereleaseUpdatesChange: (Boolean) -> Unit,
    onProfileSwitchToastsEnabledChange: (Boolean) -> Unit,
    onProfileSwitchHistoryLimitChange: (Int) -> Unit,
    onPrivilegedExecutionMethodChange: (String?) -> Unit,
    onAutoDetectPrivilegedExecutionMethod: () -> Unit,
    isShizukuPermissionGranted: Boolean,
    onRequestShizukuPermission: () -> Unit,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    var updateIntervalText by remember(settings.updateCheckIntervalDays) {
        mutableStateOf(settings.updateCheckIntervalDays.toString())
    }

    var historyLimitText by remember(settings.profileSwitchHistoryLimit) {
        mutableStateOf(settings.profileSwitchHistoryLimit.toString())
    }

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
            }
            TextButton(onClick = onBack) {
                Text("Done")
            }
        }

        SettingsSection(title = "Appearance", symbol = "palette") {
            ThemeModeSelector(
                selected = settings.colorSource,
                onChange = onColorSourceChange,
                selectedAccentColor = settings.accentColor,
                customAccentColor = settings.customAccentColor,
                onAccentColorChange = onAccentColorChange,
                onCustomAccentColorChange = onCustomAccentColorChange,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Show frequencies as percent",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Display CPU frequency values as a percentage of each cluster's selectable maximum.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.displayFrequenciesAsPercent,
                    onCheckedChange = onDisplayFrequenciesAsPercentChange,
                )
            }
        }

        SettingsSection(title = "Updates", symbol = "update") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto check",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Switch(
                    checked = settings.automaticUpdateChecksEnabled,
                    onCheckedChange = onAutomaticUpdateChecksEnabledChange,
                )
                OutlinedTextField(
                    value = updateIntervalText,
                    onValueChange = { rawValue ->
                        val digits = rawValue.filter(Char::isDigit).take(3)
                        updateIntervalText = digits
                        digits.toIntOrNull()?.let(onUpdateCheckIntervalDaysChange)
                    },
                    label = { Text("Days") },
                    enabled = settings.automaticUpdateChecksEnabled,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCheckForUpdates) {
                    Text("Check")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Include pre-releases",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Show beta and release-candidate builds when checking for updates.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.includePrereleaseUpdates,
                    onCheckedChange = onIncludePrereleaseUpdatesChange,
                )
            }
        }

        SettingsSection(title = "Quick Settings Tile", symbol = "grid_view") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Add ClusterTune to Quick Settings",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = onRequestAddQuickSettingsTile,
                    enabled = canRequestAddQuickSettingsTile && !isQuickSettingsTileAdded,
                ) {
                    Text(
                        when {
                            isQuickSettingsTileAdded -> "Tile already added"
                            canRequestAddQuickSettingsTile -> "Add tile"
                            else -> "Unavailable"
                        },
                    )
                }
            }
            SettingsControlGroup(label = "Single tap") {
                TileBehaviorSelector(
                    selected = settings.tileTapBehavior,
                    onChange = onTileTapBehaviorChange,
                )
            }
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
                        text = "Overlay permission",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (canDrawOverlays) {
                            "Granted. Quick Settings can show the compact tuner over the current app."
                        } else {
                            "Not granted. Quick Settings will use the existing dialog fallback."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onOpenOverlayPermissionSettings) {
                    Text(if (canDrawOverlays) "Manage" else "Grant")
                }
            }
        }

        SettingsSection(title = "App profiles", symbol = "notifications") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Show profile switch toast",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Show a short toast with only the profile name when app automation switches profiles.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.profileSwitchToastsEnabled,
                    onCheckedChange = onProfileSwitchToastsEnabledChange,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "History limit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Keep only the newest profile-switch entries. Older entries are removed when a new one is added.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                OutlinedTextField(
                    value = historyLimitText,
                    onValueChange = { rawValue ->
                        val digits = rawValue.filter(Char::isDigit).take(4)
                        historyLimitText = digits
                        digits.toIntOrNull()?.let { value ->
                            onProfileSwitchHistoryLimitChange(value.coerceAtMost(MAX_PROFILE_SWITCH_HISTORY_LIMIT))
                        }
                    },
                    label = { Text("Entries") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(132.dp),
                )
            }
        }

        DeviceExecutionMethodCard(
            selectedMethodId = settings.privilegedExecutionMethodId,
            isShizukuPermissionGranted = isShizukuPermissionGranted,
            onAutoDetect = onAutoDetectPrivilegedExecutionMethod,
            onMethodChange = onPrivilegedExecutionMethodChange,
            onRequestShizukuPermission = onRequestShizukuPermission,
        )

        SettingsSection(title = "Startup", symbol = "power_settings_new") {
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
                        text = "Apply last profile on device boot",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When enabled, the app will attempt to restore the last applied profile after boot.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.applyLastProfileOnBoot,
                    onCheckedChange = onApplyLastProfileOnBootChange,
                )
            }
        }

        SettingsSection(title = "Sleep", symbol = "bedtime") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Apply sleep profile",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "When enabled, ClusterTune keeps a low-priority notification so it can apply this profile when the screen turns off and restore the previous limits when the device wakes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = settings.sleepProfileEnabled,
                    onCheckedChange = onSleepProfileEnabledChange,
                    enabled = sleepProfileOptions.isNotEmpty(),
                )
            }
            if (sleepProfileOptions.isEmpty()) {
                Text(
                    text = "No profiles are available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                SettingsControlGroup(label = "Profile while asleep") {
                    SleepProfileSelector(
                        profiles = sleepProfileOptions,
                        selectedProfileId = settings.sleepProfileId,
                        enabled = settings.sleepProfileEnabled,
                        onChange = onSleepProfileChange,
                    )
                }
            }
        }

        SettingsSection(title = "Profiles", symbol = "swap_vert") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Share profiles",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Export profiles to JSON or import a shared profile file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onImportProfiles) {
                        Text("Import")
                    }
                    TextButton(onClick = onExportProfiles) {
                        Text("Export")
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Reset profiles to default",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Bundled profiles are restored and custom profiles are removed.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = { showResetConfirmation = true }) {
                    Text("Reset")
                }
            }
        }
    }


    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text("Reset profiles?") },
            text = {
                Text("This removes custom profiles and restores the bundled defaults.")
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
private fun DeviceExecutionMethodCard(
    selectedMethodId: String?,
    isShizukuPermissionGranted: Boolean,
    onAutoDetect: () -> Unit,
    onMethodChange: (String?) -> Unit,
    onRequestShizukuPermission: () -> Unit,
) {
    val selectedInfo = executionMethodInfo.firstOrNull { info -> info.id == selectedMethodId }
    val needsPermission = selectedMethodId == "shizuku" && !isShizukuPermissionGranted
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    MaterialSymbol(
                        name = "terminal",
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                        size = 24.dp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Execution method",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = selectedInfo?.appliesTo ?: "Pick how ClusterTune gets privileged access on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onAutoDetect,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    MaterialSymbol(
                        name = "auto_awesome",
                        contentDescription = null,
                        size = ButtonDefaults.IconSize,
                    )
                    Text(
                        text = "Auto detect",
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
                PrivilegedExecutionMethodSelector(
                    selectedMethodId = selectedMethodId,
                    onChange = onMethodChange,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onRequestShizukuPermission,
                    enabled = needsPermission,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    MaterialSymbol(
                        name = "security",
                        contentDescription = null,
                        size = ButtonDefaults.IconSize,
                    )
                    Text(
                        text = permissionButtonLabel(selectedMethodId, isShizukuPermissionGranted),
                        modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExecutionMethodSelectionDialog(
    selectedMethodId: String?,
    onChange: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    MaterialSymbol(
                        name = "terminal",
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                        size = 24.dp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Execution method")
                    Text(
                        text = "Choose the backend that matches this handheld.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    executionMethodInfo.take(2).forEach { info ->
                        ExecutionMethodOptionRow(
                            info = info,
                            selected = selectedMethodId == info.id,
                            onClick = {
                                onChange(info.id)
                                onDismiss()
                            },
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    executionMethodInfo.drop(2).forEach { info ->
                        ExecutionMethodOptionRow(
                            info = info,
                            selected = selectedMethodId == info.id,
                            onClick = {
                                onChange(info.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ExecutionMethodOptionRow(
    info: ExecutionMethodInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = info.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Applies to: ${info.appliesTo}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = info.note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun permissionButtonLabel(
    selectedMethodId: String?,
    isShizukuPermissionGranted: Boolean,
): String {
    return when {
        selectedMethodId != "shizuku" -> "No permission"
        isShizukuPermissionGranted -> "Granted"
        else -> "Grant"
    }
}

@Composable
private fun PrivilegedExecutionMethodSelector(
    selectedMethodId: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = selectedMethodId?.let(::executionMethodLabel) ?: "Not detected yet"

    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable { showDialog = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Change",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }

    if (showDialog) {
        ExecutionMethodSelectionDialog(
            selectedMethodId = selectedMethodId,
            onChange = onChange,
            onDismiss = { showDialog = false },
        )
    }
}

private fun executionMethodLabel(methodId: String): String {
    return when (methodId) {
        "pserver-stdout" -> "PServer direct"
        "pserver-file-output" -> "PServer compatibility"
        "root-shell" -> "Root shell"
        "shizuku" -> "Shizuku (experimental)"
        else -> methodId
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepProfileSelector(
    profiles: List<PerformanceProfile>,
    selectedProfileId: String?,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { profile -> profile.id == selectedProfileId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedProfile?.name ?: "Select profile",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name) },
                    onClick = {
                        onChange(profile.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: AppColorSource,
    onChange: (AppColorSource) -> Unit,
    selectedAccentColor: Int,
    customAccentColor: Int,
    onAccentColorChange: (Int) -> Unit,
    onCustomAccentColorChange: (Int) -> Unit,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppearancePill(
            label = "System",
            selected = selected == AppColorSource.SYSTEM,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { onChange(AppColorSource.SYSTEM) },
        )
        accentColorOptions.forEach { accentColor ->
            AccentSwatch(
                color = Color(accentColor),
                selected = selected == AppColorSource.CUSTOM_ACCENT && selectedAccentColor == accentColor,
                onClick = {
                    onChange(AppColorSource.CUSTOM_ACCENT)
                    onAccentColorChange(accentColor)
                },
            )
        }
        val customColor = Color(customAccentColor)
        AppearancePill(
            label = "Custom",
            selected = selected == AppColorSource.CUSTOM_ACCENT && selectedAccentColor == customAccentColor,
            containerColor = customColor,
            contentColor = if (customColor.luminance() > 0.5f) Color.Black else Color.White,
            onClick = {
                onChange(AppColorSource.CUSTOM_ACCENT)
                onAccentColorChange(customAccentColor)
                showColorPicker = true
            },
        )
    }

    if (showColorPicker) {
        AccentColorPickerDialog(
            initialColor = customAccentColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                onChange(AppColorSource.CUSTOM_ACCENT)
                onCustomAccentColorChange(color)
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun AppearancePill(
    label: String,
    selected: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
        tonalElevation = if (selected) 4.dp else 0.dp,
        shadowElevation = if (selected) 1.dp else 0.dp,
        border = BorderStroke(
            width = if (selected) 3.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun AccentColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit,
) {
    var red by remember(initialColor) { mutableStateOf((initialColor ushr 16) and 0xFF) }
    var green by remember(initialColor) { mutableStateOf((initialColor ushr 8) and 0xFF) }
    var blue by remember(initialColor) { mutableStateOf(initialColor and 0xFF) }
    val previewColorInt = argbColor(red, green, blue)
    val previewColor = Color(previewColorInt)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = previewColor,
                ) {
                    Text(
                        text = "Preview",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 22.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (previewColor.luminance() > 0.5f) Color.Black else Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ColorChannelSlider(
                    label = "Red",
                    value = red,
                    color = Color(0xFFB3261E),
                    onChange = { red = it },
                )
                ColorChannelSlider(
                    label = "Green",
                    value = green,
                    color = Color(0xFF006E1C),
                    onChange = { green = it },
                )
                ColorChannelSlider(
                    label = "Blue",
                    value = blue,
                    color = Color(0xFF3F51B5),
                    onChange = { blue = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(previewColorInt) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    value: Int,
    color: Color,
    onChange: (Int) -> Unit,
) {
    var textValue by remember(value) { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        val parsed = textValue.toIntOrNull()?.coerceIn(0, 255)
        if (parsed != value) textValue = value.toString()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Slider(
            modifier = Modifier.weight(1f),
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
            ),
        )
        ColorChannelValueInput(
            value = textValue,
            onValueChange = { newValue ->
                val sanitized = newValue.filter(Char::isDigit).take(3)
                textValue = sanitized
                sanitized.toIntOrNull()?.coerceIn(0, 255)?.let(onChange)
            },
        )
    }
}

@Composable
private fun ColorChannelValueInput(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.width(62.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

private fun argbColor(red: Int, green: Int, blue: Int): Int {
    return ((0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255))
}

@Composable
private fun TileBehaviorSelector(
    selected: TileInteractionBehavior,
    onChange: (TileInteractionBehavior) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileBehaviorOption(
                title = "Quick tuner",
                selected = selected == TileInteractionBehavior.SHOW_DIALOG,
                onClick = { onChange(TileInteractionBehavior.SHOW_DIALOG) },
                modifier = Modifier.weight(1f),
            )
            TileBehaviorOption(
                title = "Profile picker",
                selected = selected == TileInteractionBehavior.SHOW_PROFILE_PICKER,
                onClick = { onChange(TileInteractionBehavior.SHOW_PROFILE_PICKER) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileBehaviorOption(
                title = "Cycle profiles",
                selected = selected == TileInteractionBehavior.CYCLE_PROFILES,
                onClick = { onChange(TileInteractionBehavior.CYCLE_PROFILES) },
                modifier = Modifier.weight(1f),
            )
            TileBehaviorOption(
                title = "Open app",
                selected = selected == TileInteractionBehavior.OPEN_APP,
                onClick = { onChange(TileInteractionBehavior.OPEN_APP) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TileBehaviorOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Column(
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettingsControlGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun SettingsSection(
    title: String,
    symbol: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSectionTitle(title = title, symbol = symbol)
            content()
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String,
    symbol: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            MaterialSymbol(
                name = symbol,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(10.dp),
                size = 24.dp,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
