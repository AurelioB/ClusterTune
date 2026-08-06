package com.aure.clustertune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.aure.clustertune.R
import com.aure.clustertune.model.AppColorSource
import com.aure.clustertune.ui.AccentColorPickerDialog
import com.aure.clustertune.ui.designsystem.component.CtIcon
import com.aure.clustertune.ui.designsystem.token.ClusterTuneDensity

private data class AccentOption(val nameRes: Int, val color: Int)

private fun formatHexColorForSemantics(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

private val accentColorOptions = listOf(
    AccentOption(R.string.settings_accent_indigo, 0xFF3F51B5.toInt()),
    AccentOption(R.string.settings_accent_green, 0xFF006E1C.toInt()),
    AccentOption(R.string.settings_accent_red, 0xFFB3261E.toInt()),
    AccentOption(R.string.settings_accent_purple, 0xFF8E24AA.toInt()),
    AccentOption(R.string.settings_accent_blue, 0xFF00639A.toInt()),
    AccentOption(R.string.settings_accent_orange, 0xFF9A4600.toInt()),
)

@Composable
internal fun ThemeModeSelector(
    selected: AppColorSource,
    onChange: (AppColorSource) -> Unit,
    selectedAccentColor: Int,
    customAccentColor: Int,
    onAccentColorChange: (Int) -> Unit,
    onCustomAccentColorChange: (Int) -> Unit,
    density: ClusterTuneDensity,
) {
    var showColorPicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val systemDescription = stringResource(R.string.settings_system_color_scheme)
    val customDescription = stringResource(
        R.string.settings_edit_custom_color,
        formatHexColorForSemantics(customAccentColor),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppearanceIconBadge(
            icon = Icons.Outlined.Block,
            selected = selected == AppColorSource.SYSTEM,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            role = Role.RadioButton,
            exposeSelectedState = true,
            description = systemDescription,
            onClick = { onChange(AppColorSource.SYSTEM) },
            density = density,
        )
        val customColor = Color(customAccentColor)
        AppearanceIconBadge(
            icon = Icons.Outlined.Edit,
            selected = false,
            containerColor = customColor,
            contentColor = if (customColor.luminance() > 0.179f) Color.Black else Color.White,
            role = Role.Button,
            exposeSelectedState = false,
            description = customDescription,
            onClick = { showColorPicker = true },
            density = density,
        )
        accentColorOptions.forEach { option ->
            AccentSwatch(
                name = stringResource(option.nameRes),
                color = Color(option.color),
                selected = selected == AppColorSource.CUSTOM_ACCENT && selectedAccentColor == option.color,
                onClick = { onAccentColorChange(option.color) },
                density = density,
            )
        }
    }

    if (showColorPicker) {
        AccentColorPickerDialog(
            initialColor = customAccentColor,
            onDismiss = { showColorPicker = false },
            onColorSelected = { color ->
                onCustomAccentColorChange(color)
                showColorPicker = false
            },
        )
    }
}

@Composable
private fun AppearanceIconBadge(
    icon: ImageVector,
    selected: Boolean,
    containerColor: Color,
    contentColor: Color,
    role: Role,
    exposeSelectedState: Boolean,
    description: String,
    onClick: () -> Unit,
    density: ClusterTuneDensity,
) {
    Box(
        modifier = Modifier
            .size(density.sizing.interactiveTarget)
            .clickable(role = role, onClick = onClick)
            .semantics {
                contentDescription = description
                if (exposeSelectedState) this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(density.sizing.accentSwatchSize),
            shape = CircleShape,
            color = containerColor,
            tonalElevation = if (selected) 4.dp else 0.dp,
            shadowElevation = if (selected) 1.dp else 0.dp,
            border = BorderStroke(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CtIcon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(density.sizing.sectionIconSize),
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    density: ClusterTuneDensity,
) {
    val accentDescription = stringResource(R.string.settings_accent_description, name)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = accentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(density.sizing.accentSwatchSize)
                .background(color, CircleShape)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                CtIcon(
                    symbol = "check",
                    contentDescription = null,
                    modifier = Modifier.size(density.sizing.sectionIconSize),
                    tint = if (color.luminance() > 0.179f) Color.Black else Color.White,
                    size = density.sizing.sectionIconSize,
                )
            }
        }
    }
}
