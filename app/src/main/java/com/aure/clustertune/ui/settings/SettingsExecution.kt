package com.aure.clustertune.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.aure.clustertune.R
import com.aure.clustertune.ui.designsystem.component.CtIcon
import com.aure.clustertune.ui.designsystem.component.CtSelectableRow
import com.aure.clustertune.ui.designsystem.component.CtSectionCard
import com.aure.clustertune.ui.designsystem.token.ClusterTuneDensity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextOverflow
import com.aure.clustertune.ui.designsystem.component.CtSwitchPreference

private data class ExecutionMethodInfo(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
)

private val executionMethodInfo = listOf(
    ExecutionMethodInfo(
        id = "pserver-stdout",
        labelRes = R.string.settings_execution_pserver,
        descriptionRes = R.string.settings_execution_pserver_description,
    ),
    ExecutionMethodInfo(
        id = "root-shell",
        labelRes = R.string.settings_execution_root,
        descriptionRes = R.string.settings_execution_root_description,
    ),
    ExecutionMethodInfo(
        id = "jdwp-inject",
        labelRes = R.string.settings_execution_jdwp,
        descriptionRes = R.string.settings_execution_jdwp_description,
    ),
)

@Composable
internal fun DeviceExecutionMethodCard(
    selectedMethodId: String?,
    onAutoDetect: () -> Unit,
    onMethodChange: (String?) -> Unit,
    density: ClusterTuneDensity,
    /**
     * Opens the wireless-debugging pairing screen. Null hides the button, so
     * callers that have no navigation host (previews, tests) need no change.
     */
    onOpenWirelessDebugSetup: (() -> Unit)? = null,
    /** True while the privileged host is running and serving requests. */
    isHostRunning: () -> Boolean = { false },
    /** Opt-in diagnostics for this execution method. */
    wirelessDebugLoggingEnabled: Boolean = false,
    onWirelessDebugLoggingChange: (Boolean) -> Unit = {},
    onViewDiagnosticLog: () -> Unit = {},
    onDownloadDiagnosticLog: () -> Unit = {},
) {
    SectionCard(title = stringResource(R.string.settings_execution), symbol = "terminal", density = density) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onAutoDetect,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                CtIcon(
                    symbol = "auto_awesome",
                    contentDescription = null,
                    size = ButtonDefaults.IconSize,
                )
                Text(
                    text = stringResource(R.string.settings_auto_detect),
                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                )
            }
            PrivilegedExecutionMethodSelector(
                selectedMethodId = selectedMethodId,
                onChange = onMethodChange,
                modifier = Modifier.weight(1f),
            )
        }
        if (selectedMethodId == "jdwp-inject" && onOpenWirelessDebugSetup != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenWirelessDebugSetup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_execution_jdwp_setup))
            }
            // The adb connection and the privileged host are independent: once
            // the host is up it serves over Binder and needs no network, so
            // reporting only the connection state would call a fully working
            // setup "not connected" the moment Wi-Fi goes off.
            if (isHostRunning()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.settings_execution_jdwp_host_running),
                    fontWeight = FontWeight.Bold,
                )
            }

            // Diagnostics are opt-in and live here rather than in a general
            // section, because they only describe this execution method and are
            // meaningless when another one is selected.
            Spacer(Modifier.height(10.dp))
            CtSwitchPreference(
                title = { Text(stringResource(R.string.settings_execution_jdwp_logging)) },
                description = {
                    Text(stringResource(R.string.settings_execution_jdwp_logging_summary))
                },
                checked = wirelessDebugLoggingEnabled,
                onCheckedChange = onWirelessDebugLoggingChange,
            )
            if (wirelessDebugLoggingEnabled) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onViewDiagnosticLog,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_execution_jdwp_logging_view)) }
                    OutlinedButton(
                        onClick = onDownloadDiagnosticLog,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.settings_execution_jdwp_logging_download)) }
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
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .widthIn(max = 720.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.settings_execution_method)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                executionMethodInfo.forEach { info ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun ExecutionMethodOptionRow(
    info: ExecutionMethodInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CtSelectableRow(
        title = {
            Text(
                text = stringResource(info.labelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        description = {
            Text(
                text = stringResource(info.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrivilegedExecutionMethodSelector(
    selectedMethodId: String?,
    onChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val selectedLabel = when (selectedMethodId) {
        "pserver-stdout" -> stringResource(R.string.settings_execution_pserver)
        "root-shell" -> stringResource(R.string.settings_execution_root)
        "jdwp-inject" -> stringResource(R.string.settings_execution_jdwp)
        null -> stringResource(R.string.settings_execution_not_selected)
        else -> selectedMethodId
    }

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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // weight + ellipsis: long labels (e.g. "Wireless debugging
                    // (no root)") otherwise overflow and collide with the
                    // trailing "Change" text instead of truncating, which reads
                    // as garbled overlapping glyphs.
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    text = selectedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.settings_change),
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

@Composable
private fun SectionCard(
    title: String,
    symbol: String,
    density: ClusterTuneDensity,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    CtSectionCard(
        contentPadding = density.spacing.cardPadding.let { androidx.compose.foundation.layout.PaddingValues(it) },
        verticalArrangement = Arrangement.spacedBy(density.spacing.contentGap),
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
        content()
    }
}
