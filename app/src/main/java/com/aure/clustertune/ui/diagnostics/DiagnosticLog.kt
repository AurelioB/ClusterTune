package com.aure.clustertune.ui.diagnostics

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wuyr.jdwp_injector.debug.JdwpDebugLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of the diagnostic log, with copy and clear.
 *
 * Extracted from the wireless-debug setup screen so the same view can be opened
 * from Settings. Both entry points share this one implementation; the setup
 * screen previously owned the only copy, which meant the log was unreachable
 * unless you happened to be mid-pairing.
 */
@Composable
fun DiagnosticLogDialog(onDismiss: () -> Unit) {
    var logLines by remember { mutableStateOf(JdwpDebugLog.snapshot()) }
    DisposableEffect(Unit) {
        JdwpDebugLog.setListener { logLines = JdwpDebugLog.snapshot() }
        onDispose { JdwpDebugLog.setListener(null) }
    }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostic log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = logLines.joinToString("\n").ifEmpty {
                        "(no log yet — reproduce the problem with logging on, then reopen this)"
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Text(
                    text = "${logLines.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Column {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(logLines.joinToString("\n")))
                    },
                ) { Text("Copy") }
                TextButton(onClick = { JdwpDebugLog.clear() }) { Text("Clear") }
            }
        },
    )
}

/**
 * Writes the log to a timestamped file and returns a message describing where it
 * went, or why it could not be written.
 *
 * Documents/ClusterScripts is used because this app already reads and writes
 * there for the privileged handoff, so it is known to work without any runtime
 * permission and is reachable from a file manager — which matters, since the
 * people who most need this have no PC attached.
 */
@Suppress("DEPRECATION")
fun exportDiagnosticLog(context: Context): String {
    val text = JdwpDebugLog.exportText()
        ?: return "Nothing to export yet — reproduce the problem first."
    return runCatching {
        val dir = File(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "ClusterScripts",
            ),
            "logs",
        )
        if (!dir.exists() && !dir.mkdirs()) return "Could not create ${dir.absolutePath}"
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "clustertune-$stamp.log")
        file.writeText(buildString {
            appendLine("ClusterTune diagnostic log")
            appendLine("captured: $stamp")
            appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("android: ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})")
            appendLine("app uid: ${android.os.Process.myUid()}")
            appendLine("---")
            append(text)
        })
        file.setReadable(true, false)
        "Saved to ${file.absolutePath}"
    }.getOrElse { "Export failed: ${it.message}" }
}
