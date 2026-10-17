package com.aure.clustertune.logging

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun historyExportText(
    entries: List<ProfileSwitchHistoryEntry>,
    appVersion: String,
    device: String,
    androidVersion: String,
): String = buildString {
    appendLine("ClusterTune history")
    appendLine("App version: $appVersion")
    appendLine("Device: $device")
    appendLine("Android: $androidVersion")
    appendLine("Entries: ${entries.size} (newest first)")
    appendLine()
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
    entries.forEach { entry ->
        appendLine("${formatter.format(Date(entry.timestampMillis))} [${entry.eventType.label}] ${entry.profileName}")
        appendLine(entry.trigger)
        appendLine()
    }
}

/** One cache file, rewritten only when Share is pressed; no background export or new permission. */
internal suspend fun createHistoryShareIntent(context: Context, entries: List<ProfileSwitchHistoryEntry>): Intent =
    withContext(Dispatchers.IO) {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val directory = File(context.cacheDir, "history").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "clustertune-history.txt")
        file.writeText(historyExportText(entries, appVersion, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ClusterTune history")
            clipData = ClipData.newUri(context.contentResolver, "ClusterTune history", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
