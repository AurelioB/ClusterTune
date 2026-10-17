package com.aure.clustertune.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import android.widget.Toast
import com.aure.clustertune.logging.createHistoryShareIntent
import com.aure.clustertune.model.HistoryEventType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ProfileSwitchHistorySection(
    entries: List<ProfileSwitchHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    val timestampFormatter = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent events", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = entries.isNotEmpty() && !sharing, onClick = {
                val snapshot = entries.toList()
                sharing = true
                scope.launch {
                    try {
                        val intent = createHistoryShareIntent(context, snapshot)
                        context.startActivity(Intent.createChooser(intent, "Share ClusterTune history"))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, "Unable to share history", Toast.LENGTH_LONG).show()
                    } finally { sharing = false }
                }
            }) { Text("Share") }
        }
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No events logged yet.", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(items = entries, key = { index, entry -> "${entry.timestampMillis}-$index" }) { _, entry ->
                    ProfileSwitchHistoryRow(entry, timestampFormatter.format(Date(entry.timestampMillis)))
                }
            }
        }
    }
}

@Composable
private fun ProfileSwitchHistoryRow(entry: ProfileSwitchHistoryEntry, timestamp: String) {
    val colorScheme = MaterialTheme.colorScheme
    val rowShape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = rowShape,
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.46f),
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val eventColor = if (entry.eventType == HistoryEventType.ERROR) colorScheme.error else colorScheme.primary
                Surface(shape = RoundedCornerShape(6.dp), color = eventColor.copy(alpha = 0.12f)) {
                    Text(entry.eventType.label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall, color = eventColor)
                }
                Text(entry.profileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timestamp, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f), textAlign = TextAlign.End, maxLines = 1)
            }
            Text(entry.trigger, style = MaterialTheme.typography.bodySmall, color = colorScheme.onSurfaceVariant.copy(alpha = 0.84f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
