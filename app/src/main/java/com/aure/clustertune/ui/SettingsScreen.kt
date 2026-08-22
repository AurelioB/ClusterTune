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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aure.clustertune.ui.designsystem.component.CtIcon
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.aure.clustertune.R
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.MAX_EDGE_HANDLE_HEIGHT_DP
import com.aure.clustertune.model.MAX_EDGE_HANDLE_OPACITY_PERCENT
import com.aure.clustertune.model.MAX_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MIN_EDGE_HANDLE_HEIGHT_DP
import com.aure.clustertune.model.MIN_EDGE_HANDLE_OPACITY_PERCENT
import com.aure.clustertune.model.MIN_EDGE_HANDLE_THICKNESS_DP
import com.aure.clustertune.model.MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TileInteractionBehavior
import kotlin.math.roundToInt
import com.aure.clustertune.ui.designsystem.component.CtNumericField
import com.aure.clustertune.ui.designsystem.component.CtCompactOutlinedField
import com.aure.clustertune.ui.designsystem.component.CtConfirmationDialog
import com.aure.clustertune.ui.designsystem.component.CtPreferenceRow
import com.aure.clustertune.ui.designsystem.component.CtSelectableRow
import com.aure.clustertune.ui.designsystem.component.CtSelectionIndicator
import com.aure.clustertune.ui.designsystem.component.CtSectionCard
import com.aure.clustertune.ui.designsystem.component.CtSlider
import com.aure.clustertune.ui.designsystem.component.CtSwitchPreference
import com.aure.clustertune.ui.designsystem.token.ClusterTuneBreakpoints
import com.aure.clustertune.ui.designsystem.token.ClusterTuneDensity
import com.aure.clustertune.ui.settings.ThemeModeSelector
import com.aure.clustertune.ui.settings.DeviceExecutionMethodCard

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
    canDrawOverlays: Boolean,
    onOpenOverlayPermissionSettings: () -> Unit,
    hasUsageAccess: Boolean,
    onOpenUsageAccessSettings: () -> Unit,
    hasAppProfileAccessibilityAccess: Boolean,
    onOpenAppProfileAccessibilitySettings: () -> Unit,
    /** Opens the wireless-debugging pairing screen; null hides the entry point. */
    onOpenWirelessDebugSetup: (() -> Unit)? = null,
    /** True while the privileged host is running and serving requests. */
    isHostRunning: () -> Boolean = { false },
    onWirelessDebugLoggingChange: (Boolean) -> Unit = {},
    onViewDiagnosticLog: () -> Unit = {},
    onDownloadDiagnosticLog: () -> Unit = {},
    hasNotificationAccess: Boolean,
    onOpenNotificationSettings: () -> Unit,
    canInstallUpdates: Boolean,
    onOpenInstallPermissionSettings: () -> Unit,
    onLeftEdgeProfilePickerEnabledChange: (Boolean) -> Unit,
    onEdgeHandlePreview: (Int, Int, Int, Int) -> Unit,
    onEdgeHandleHeightChange: (Int) -> Unit,
    onEdgeHandleThicknessChange: (Int) -> Unit,
    onEdgeHandleVerticalPositionChange: (Int) -> Unit,
    onEdgeHandleOpacityChange: (Int) -> Unit,
    onCheckForUpdates: () -> Unit,
    onAutomaticUpdateChecksEnabledChange: (Boolean) -> Unit,
    onUpdateCheckIntervalDaysChange: (Int) -> Unit,
    onIncludePrereleaseUpdatesChange: (Boolean) -> Unit,
    onProfileSwitchToastsEnabledChange: (Boolean) -> Unit,
    onProfileSwitchHistoryLimitChange: (Int) -> Unit,
    onPrivilegedExecutionMethodChange: (String?) -> Unit,
    onAutoDetectPrivilegedExecutionMethod: () -> Unit,
) {
    var showResetConfirmation by remember { mutableStateOf(false) }

    var updateIntervalText by remember(settings.updateCheckIntervalDays) {
        mutableStateOf(settings.updateCheckIntervalDays.toString())
    }

    var historyLimitText by remember(settings.profileSwitchHistoryLimit) {
        mutableStateOf(settings.profileSwitchHistoryLimit.toString())
    }

    val configuration = LocalConfiguration.current
    val density = ClusterTuneBreakpoints.densityFor(
        configuration.screenWidthDp.dp,
        configuration.screenHeightDp.dp,
    )
    val spacing = density.spacing
    val usesTwoColumns = ClusterTuneBreakpoints.usesTwoColumnSettings(
        configuration.screenWidthDp.dp,
        configuration.screenHeightDp.dp,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(spacing.sectionGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.settings_done))
            }
        }

        SettingsSectionsLayout(twoColumns = usesTwoColumns, sectionGap = spacing.sectionGap) {
        SectionCard(title = stringResource(R.string.settings_appearance), symbol = "palette", density = density) {
            ThemeModeSelector(
                selected = settings.colorSource,
                onChange = onColorSourceChange,
                selectedAccentColor = settings.accentColor,
                customAccentColor = settings.customAccentColor,
                onAccentColorChange = onAccentColorChange,
                onCustomAccentColorChange = onCustomAccentColorChange,
                density = density,
            )
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_use_percentages), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                description = { Text(stringResource(R.string.settings_use_percentages_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                checked = settings.displayFrequenciesAsPercent,
                onCheckedChange = onDisplayFrequenciesAsPercentChange,
            )
        }

        SectionCard(title = stringResource(R.string.settings_updates), symbol = "update", density = density) {
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_check_automatically), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                checked = settings.automaticUpdateChecksEnabled,
                onCheckedChange = onAutomaticUpdateChecksEnabledChange,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CtNumericField(
                    value = updateIntervalText,
                    onValueChange = { rawValue ->
                        val digits = rawValue.filter(Char::isDigit).take(3)
                        updateIntervalText = digits
                        digits.toIntOrNull()?.let(onUpdateCheckIntervalDaysChange)
                    },
                    label = { Text(stringResource(R.string.settings_every_days)) },
                    enabled = settings.automaticUpdateChecksEnabled,
                    maxDigits = 3,
                    containerHeight = density.sizing.numericFieldHeight,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCheckForUpdates) {
                    Text(stringResource(R.string.settings_check_now))
                }
            }
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_include_prereleases), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                checked = settings.includePrereleaseUpdates,
                onCheckedChange = onIncludePrereleaseUpdatesChange,
            )
            SettingsAccessRow(
                title = stringResource(R.string.settings_install_downloaded_updates),
                description = stringResource(R.string.settings_install_downloaded_updates_description),
                granted = canInstallUpdates,
                missingActionLabel = stringResource(R.string.settings_allow),
                onClick = onOpenInstallPermissionSettings,
            )
        }

        SectionCard(title = stringResource(R.string.settings_permissions_access), symbol = "shield", density = density) {
            SettingsAccessRow(
                title = stringResource(R.string.settings_overlay_access),
                description = stringResource(R.string.settings_overlay_access_description),
                granted = canDrawOverlays,
                onClick = onOpenOverlayPermissionSettings,
                missingActionLabel = stringResource(R.string.settings_grant),
            )
            SettingsAccessRow(
                title = stringResource(R.string.settings_app_profile_accessibility),
                description = stringResource(R.string.settings_app_profile_accessibility_description),
                granted = hasAppProfileAccessibilityAccess,
                onClick = onOpenAppProfileAccessibilitySettings,
                missingActionLabel = stringResource(R.string.settings_grant),
            )
            SettingsAccessRow(
                title = stringResource(R.string.settings_usage_access),
                description = stringResource(R.string.settings_usage_access_description),
                granted = hasUsageAccess,
                onClick = onOpenUsageAccessSettings,
                missingActionLabel = stringResource(R.string.settings_grant),
            )
            SettingsAccessRow(
                title = stringResource(R.string.settings_notifications),
                description = stringResource(R.string.settings_notifications_description),
                granted = hasNotificationAccess,
                onClick = onOpenNotificationSettings,
                missingActionLabel = stringResource(R.string.settings_grant),
            )
        }

        SectionCard(title = stringResource(R.string.settings_quick_access), symbol = "grid_view", density = density) {
            if (canRequestAddQuickSettingsTile) {
                OutlinedButton(
                    onClick = onRequestAddQuickSettingsTile,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_add_tile))
                }
            }
            Text(
                text = stringResource(R.string.settings_add_tile_from_editor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsControlGroup(label = stringResource(R.string.settings_single_tap)) {
                TileBehaviorSelector(
                    selected = settings.tileTapBehavior,
                    onChange = onTileTapBehaviorChange,
                )
            }
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_left_edge_profile_picker), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                description = { Text(stringResource(R.string.settings_left_edge_profile_picker_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                checked = settings.leftEdgeProfilePickerEnabled,
                onCheckedChange = onLeftEdgeProfilePickerEnabledChange,
            )
            if (settings.leftEdgeProfilePickerEnabled) {
                EdgeHandleControls(
                    heightDp = settings.edgeHandleHeightDp,
                    thicknessDp = settings.edgeHandleThicknessDp,
                    verticalPositionPercent = settings.edgeHandleVerticalPositionPercent,
                    opacityPercent = settings.edgeHandleOpacityPercent,
                    onPreview = onEdgeHandlePreview,
                    onHeightChange = onEdgeHandleHeightChange,
                    onThicknessChange = onEdgeHandleThicknessChange,
                    onVerticalPositionChange = onEdgeHandleVerticalPositionChange,
                    onOpacityChange = onEdgeHandleOpacityChange,
                )
            }
        }

        SectionCard(title = stringResource(R.string.settings_automation), symbol = "routine", density = density) {
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_apply_last_profile_after_boot), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                checked = settings.applyLastProfileOnBoot,
                onCheckedChange = onApplyLastProfileOnBootChange,
            )
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_use_sleep_profile), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (sleepProfileOptions.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) },
                description = { Text(stringResource(R.string.settings_use_sleep_profile_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                checked = settings.sleepProfileEnabled,
                onCheckedChange = onSleepProfileEnabledChange,
                enabled = sleepProfileOptions.isNotEmpty(),
            )
            if (sleepProfileOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_create_profile_to_enable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SettingsControlGroup(label = stringResource(R.string.settings_while_asleep)) {
                    SleepProfileSelector(
                        profiles = sleepProfileOptions,
                        selectedProfileId = settings.sleepProfileId,
                        enabled = settings.sleepProfileEnabled,
                        containerHeight = density.sizing.numericFieldHeight,
                        onChange = onSleepProfileChange,
                    )
                }
            }
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_show_profile_name_on_switch), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) },
                checked = settings.profileSwitchToastsEnabled,
                onCheckedChange = onProfileSwitchToastsEnabledChange,
            )
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
                        text = stringResource(R.string.settings_switch_history),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.settings_switch_history_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CtNumericField(
                    value = historyLimitText,
                    onValueChange = { rawValue ->
                        val digits = rawValue.filter(Char::isDigit).take(4)
                        historyLimitText = digits
                        digits.toIntOrNull()?.let { value ->
                            onProfileSwitchHistoryLimitChange(value.coerceAtMost(MAX_PROFILE_SWITCH_HISTORY_LIMIT))
                        }
                    },
                    label = { Text(stringResource(R.string.settings_entries)) },
                    maxDigits = 4,
                    containerHeight = density.sizing.numericFieldHeight,
                    modifier = Modifier.width(132.dp),
                )
            }
        }

        DeviceExecutionMethodCard(
            selectedMethodId = settings.privilegedExecutionMethodId,
            onAutoDetect = onAutoDetectPrivilegedExecutionMethod,
            onMethodChange = onPrivilegedExecutionMethodChange,
            density = density,
            onOpenWirelessDebugSetup = onOpenWirelessDebugSetup,
            isHostRunning = isHostRunning,
            wirelessDebugLoggingEnabled = settings.wirelessDebugLoggingEnabled,
            onWirelessDebugLoggingChange = onWirelessDebugLoggingChange,
            onViewDiagnosticLog = onViewDiagnosticLog,
            onDownloadDiagnosticLog = onDownloadDiagnosticLog,
        )

        SectionCard(title = stringResource(R.string.settings_profiles), symbol = "swap_vert", density = density) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onImportProfiles,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_import))
                }
                OutlinedButton(
                    onClick = onExportProfiles,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_export))
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
                        text = stringResource(R.string.settings_restore_defaults),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.settings_removes_custom_profiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showResetConfirmation = true }) {
                    Text(stringResource(R.string.settings_reset))
                }
            }
        }
        }
    }


    if (showResetConfirmation) {
        CtConfirmationDialog(
            title = stringResource(R.string.settings_reset_profiles_title),
            message = stringResource(R.string.settings_reset_profiles_message),
            confirmLabel = stringResource(R.string.settings_reset),
            dismissLabel = stringResource(R.string.settings_cancel),
            onConfirm = {
                showResetConfirmation = false
                onResetProfiles()
            },
            onDismissRequest = { showResetConfirmation = false },
        )
    }
}

// Kept in the ui package for TunerScreen's status label; the selector implementation
// itself lives with the settings feature components.
internal fun executionMethodLabel(methodId: String): String = when (methodId) {
    "pserver-stdout" -> "PServer"
    "root-shell" -> "Root"
    else -> methodId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepProfileSelector(
    profiles: List<PerformanceProfile>,
    selectedProfileId: String?,
    enabled: Boolean,
    containerHeight: Dp,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { profile -> profile.id == selectedProfileId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        CtCompactOutlinedField(
            value = selectedProfile?.name ?: stringResource(R.string.settings_select_profile),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            containerHeight = containerHeight,
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .fillMaxWidth(),
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
                title = stringResource(R.string.settings_quick_tuner),
                selected = selected == TileInteractionBehavior.SHOW_DIALOG,
                onClick = { onChange(TileInteractionBehavior.SHOW_DIALOG) },
                modifier = Modifier.weight(1f),
            )
            TileBehaviorOption(
                title = stringResource(R.string.settings_profile_picker),
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
                title = stringResource(R.string.settings_cycle_profiles),
                selected = selected == TileInteractionBehavior.CYCLE_PROFILES,
                onClick = { onChange(TileInteractionBehavior.CYCLE_PROFILES) },
                modifier = Modifier.weight(1f),
            )
            TileBehaviorOption(
                title = stringResource(R.string.settings_open_app),
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
    CtPreferenceRow(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier = modifier,
        leading = {
            CtSelectionIndicator(
                selected = selected,
                size = 20.dp,
            )
        },
        onClick = onClick,
        minimumHeight = 48.dp,
        horizontalContentSpacing = 4.dp,
    )
}

@Composable
private fun EdgeHandleControls(
    heightDp: Int,
    thicknessDp: Int,
    verticalPositionPercent: Int,
    opacityPercent: Int,
    onPreview: (Int, Int, Int, Int) -> Unit,
    onHeightChange: (Int) -> Unit,
    onThicknessChange: (Int) -> Unit,
    onVerticalPositionChange: (Int) -> Unit,
    onOpacityChange: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EdgeHandleSlider(
                    label = stringResource(R.string.settings_height),
                    value = heightDp,
                    valueRange = MIN_EDGE_HANDLE_HEIGHT_DP..MAX_EDGE_HANDLE_HEIGHT_DP,
                    valueText = { stringResource(R.string.settings_dp_value, it) },
                    onValuePreview = { previewHeightDp ->
                        onPreview(
                            previewHeightDp,
                            thicknessDp,
                            verticalPositionPercent,
                            opacityPercent,
                        )
                    },
                    onValueChangeFinished = onHeightChange,
                    modifier = Modifier.weight(1f),
                )
                EdgeHandleSlider(
                    label = stringResource(R.string.settings_thickness),
                    value = thicknessDp,
                    valueRange = MIN_EDGE_HANDLE_THICKNESS_DP..MAX_EDGE_HANDLE_THICKNESS_DP,
                    valueText = { stringResource(R.string.settings_dp_value, it) },
                    onValuePreview = { previewThicknessDp ->
                        onPreview(
                            heightDp,
                            previewThicknessDp,
                            verticalPositionPercent,
                            opacityPercent,
                        )
                    },
                    onValueChangeFinished = onThicknessChange,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EdgeHandleSlider(
                    label = stringResource(R.string.settings_position),
                    value = verticalPositionPercent,
                    valueRange = MIN_EDGE_HANDLE_VERTICAL_POSITION_PERCENT..
                        MAX_EDGE_HANDLE_VERTICAL_POSITION_PERCENT,
                    valueText = { stringResource(R.string.settings_percent_value, it) },
                    onValuePreview = { previewPositionPercent ->
                        onPreview(
                            heightDp,
                            thicknessDp,
                            previewPositionPercent,
                            opacityPercent,
                        )
                    },
                    onValueChangeFinished = onVerticalPositionChange,
                    modifier = Modifier.weight(1f),
                )
                EdgeHandleSlider(
                    label = stringResource(R.string.settings_opacity),
                    value = opacityPercent,
                    valueRange = MIN_EDGE_HANDLE_OPACITY_PERCENT..MAX_EDGE_HANDLE_OPACITY_PERCENT,
                    valueText = { stringResource(R.string.settings_percent_value, it) },
                    onValuePreview = { previewOpacityPercent ->
                        onPreview(
                            heightDp,
                            thicknessDp,
                            verticalPositionPercent,
                            previewOpacityPercent,
                        )
                    },
                    onValueChangeFinished = onOpacityChange,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = stringResource(R.string.settings_zero_opacity_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EdgeHandleSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    valueText: @Composable (Int) -> String,
    onValuePreview: (Int) -> Unit,
    onValueChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var lastEmittedValue by remember(value) { mutableIntStateOf(value) }
    val roundedValue = pendingValue.roundToInt()

    Column(
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = valueText(roundedValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CtSlider(
            value = pendingValue,
            onValueChange = {
                pendingValue = it
                val rounded = it.roundToInt()
                if (rounded != lastEmittedValue) {
                    lastEmittedValue = rounded
                    onValuePreview(rounded)
                }
            },
            onValueChangeFinished = {
                onValueChangeFinished(pendingValue.roundToInt())
            },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        )
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
private fun SettingsSectionsLayout(
    twoColumns: Boolean,
    sectionGap: Dp,
    content: @Composable () -> Unit,
) {
    if (!twoColumns) {
        Column(verticalArrangement = Arrangement.spacedBy(sectionGap)) { content() }
        return
    }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = content,
    ) { measurables, constraints ->
        val gap = sectionGap.roundToPx()
        val columnWidth = ((constraints.maxWidth - gap) / 2).coerceAtLeast(0)
        val childConstraints = constraints.copy(minWidth = 0, maxWidth = columnWidth)
        val placeables = measurables.map { it.measure(childConstraints) }
        val leftIndices = setOf(0, 3, 4)
        val leftHeight = placeables.indices.filter { it in leftIndices }.sumOf { placeables[it].height } +
            gap * (leftIndices.size - 1)
        val rightIndices = placeables.indices.filterNot { it in leftIndices }
        val rightHeight = rightIndices.sumOf { placeables[it].height } + gap * (rightIndices.size - 1)
        val height = maxOf(leftHeight, rightHeight).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            var leftY = 0
            var rightY = 0
            placeables.forEachIndexed { index, placeable ->
                if (index in leftIndices) {
                    placeable.placeRelative(0, leftY)
                    leftY += placeable.height + gap
                } else {
                    placeable.placeRelative(columnWidth + gap, rightY)
                    rightY += placeable.height + gap
                }
            }
        }
    }
}

@Composable
private fun SettingsAccessRow(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit,
    missingActionLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) {
            Text(if (granted) stringResource(R.string.settings_manage) else missingActionLabel)
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    symbol: String,
    density: ClusterTuneDensity,
    content: @Composable ColumnScope.() -> Unit,
) {
    CtSectionCard(
        contentPadding = density.spacing.cardPadding.let { androidx.compose.foundation.layout.PaddingValues(it) },
        verticalArrangement = Arrangement.spacedBy(density.spacing.contentGap),
    ) {
        SettingsSectionTitle(title = title, symbol = symbol, density = density)
        content()
    }
}

@Composable
private fun SettingsSectionTitle(
    title: String,
    symbol: String,
    density: ClusterTuneDensity,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            CtIcon(
                symbol = symbol,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(
                    (density.sizing.sectionIconContainerSize - density.sizing.sectionIconSize) / 2,
                ),
                size = density.sizing.sectionIconSize,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
