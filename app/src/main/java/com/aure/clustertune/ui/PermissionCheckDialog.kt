package com.aure.clustertune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.aure.clustertune.R
import com.aure.clustertune.permissions.AppAccess
import com.aure.clustertune.ui.designsystem.component.CtSectionCard

/** Explains missing app access and offers a route to each corresponding system setting. */
@Composable
fun PermissionCheckDialog(
    missingAccess: List<AppAccess>,
    onFixAccess: (AppAccess) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(0.94f),
        title = { Text(stringResource(R.string.permission_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_dialog_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                missingAccess.forEach { access ->
                    CtSectionCard(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(text = access.title(), style = MaterialTheme.typography.titleMedium)
                                Text(text = access.purpose(), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = access.instructions(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onFixAccess(access) }) {
                                Text(stringResource(R.string.permission_dialog_fix))
                            }
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.permission_dialog_review_later),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_dialog_not_now)) }
        },
    )
}

@Composable
private fun AppAccess.title(): String = stringResource(
    when (this) {
        AppAccess.OVERLAY -> R.string.permission_dialog_overlay_title
        AppAccess.ACCESSIBILITY -> R.string.permission_dialog_accessibility_title
        AppAccess.USAGE -> R.string.permission_dialog_usage_title
        AppAccess.NOTIFICATIONS -> R.string.permission_dialog_notifications_title
    },
)

@Composable
private fun AppAccess.purpose(): String = stringResource(
    when (this) {
        AppAccess.OVERLAY -> R.string.settings_overlay_access_description
        AppAccess.ACCESSIBILITY -> R.string.settings_app_profile_accessibility_description
        AppAccess.USAGE -> R.string.settings_usage_access_description
        AppAccess.NOTIFICATIONS -> R.string.settings_notifications_description
    },
)

@Composable
private fun AppAccess.instructions(): String = stringResource(
    when (this) {
        AppAccess.OVERLAY -> R.string.permission_dialog_overlay_instructions
        AppAccess.ACCESSIBILITY -> R.string.permission_dialog_accessibility_instructions
        AppAccess.USAGE -> R.string.permission_dialog_usage_instructions
        AppAccess.NOTIFICATIONS -> R.string.permission_dialog_notifications_instructions
    },
)
