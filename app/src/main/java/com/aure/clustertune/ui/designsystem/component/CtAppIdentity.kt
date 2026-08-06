package com.aure.clustertune.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Compact, domain-neutral identity treatment for an application or account. */
@Composable
internal fun CtAppIdentity(
    label: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    iconSize: Dp = 48.dp,
    compact: Boolean = false,
    labelStyle: TextStyle? = null,
    labelFontWeight: FontWeight? = null,
    subtitleStyle: TextStyle? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        icon?.let {
            Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) { it() }
            Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp)) {
            Text(
                text = label,
                style = labelStyle
                    ?: if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = labelFontWeight,
                maxLines = 1,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = subtitleStyle ?: MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
