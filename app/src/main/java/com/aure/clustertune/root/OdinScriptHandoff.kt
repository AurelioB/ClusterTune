package com.aure.clustertune.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Workaround for the Odin 2 Mini's broken pservice.
 *
 * On the Mini, pservice (the binder service ClusterTune uses to run
 * commands as root on the rest of the Odin family) is broken: binder
 * transactions complete without throwing, but the reply payload is
 * always empty. Writes look like they succeed at the API layer but
 * nothing reaches the kernel. AYN provides a workaround in their
 * customised Settings app: a "Run script as Root" page that executes
 * a `.sh` from internal storage with full privileges. We can't call
 * that page directly (the activity is not exported), but we can write
 * the script to a location Odin Settings can read, launch Odin
 * Settings, and let the user navigate the remaining few taps
 * themselves.
 *
 * Where the script goes: `/sdcard/Documents/ClusterScripts/`. This is
 * a public storage directory that any app can create files in without
 * runtime permissions (a deliberate scoped-storage carve-out on
 * Android 10+ that allows apps to create files they own in Documents
 * and Downloads). Odin Settings's file picker treats Documents as a
 * top-level location and navigates into it cleanly. Earlier
 * iterations tried `/sdcard/Download/` (worked but the file was hard
 * for the user to locate) and the app-scoped equivalent under
 * `/sdcard/Android/data/<pkg>/files/`, which is unlistable by other
 * apps on Android 11+.
 */
class OdinScriptHandoff(private val context: Context) {

    /**
     * True if the device exposes the AYN-customised Settings app with
     * a "Run script as Root" feature. Currently this is the Odin 2/3
     * family and any future AYN device that ships the same package.
     */
    val isAvailable: Boolean
        get() = isPackageInstalled(ODIN_SETTINGS_PACKAGE)

    /**
     * Absolute path the script will be written to. Resolved lazily
     * because it depends on Environment state.
     */
    fun absoluteScriptPath(): String = File(scriptDirectory(), SCRIPT_FILENAME).absolutePath

    /**
     * The same path with the storage-root prefix stripped, shown to
     * the user in the tutorial. Odin Settings's file picker presents
     * Documents as a top-level entry, so users only ever see this
     * shorter form.
     */
    fun userVisibleScriptPath(): String =
        "Documents/$SCRIPT_DIR_NAME/$SCRIPT_FILENAME"

    /**
     * Writes the apply script to the handoff location and returns its
     * absolute path. Logs the outcome under tag `ClusterTuneHandoff`
     * regardless. Returns null only if the write itself threw.
     */
    fun writeScript(scriptContents: String): String? {
        return runCatching {
            val dir = scriptDirectory()
            if (!dir.exists()) {
                val created = dir.mkdirs()
                Log.d(TAG, "Created handoff dir ${dir.absolutePath}: $created")
            }
            val file = File(dir, SCRIPT_FILENAME)
            file.writeText(scriptContents)
            file.setReadable(true, false)
            file.setExecutable(true, false)
            Log.d(
                TAG,
                "Wrote handoff script to ${file.absolutePath} " +
                    "(${scriptContents.length} bytes, exists=${file.exists()}, " +
                    "canRead=${file.canRead()}, canExec=${file.canExecute()})",
            )
            // Ask MediaScanner to index the file so it also appears when
            // the user browses Documents via the chip-shortcut view in
            // Android's Files app (the root-storage browser already
            // shows it via the regular filesystem listing, but the
            // chips query MediaStore and would miss our file otherwise).
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("application/x-sh"),
                    null,
                )
            } catch (throwable: Throwable) {
                Log.d(TAG, "MediaScanner request failed (non-fatal)", throwable)
            }
            file.absolutePath
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to write handoff script", throwable)
        }.getOrNull()
    }

    fun launchOdinSettings() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(ODIN_SETTINGS_PACKAGE, ODIN_SETTINGS_MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to launch Odin Settings, falling back to system Settings", throwable)
            try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (inner: Throwable) {
                Log.w(TAG, "Failed to launch system Settings too", inner)
            }
        }
    }

    private fun scriptDirectory(): File {
        @Suppress("DEPRECATION")
        val documents = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        return File(documents, SCRIPT_DIR_NAME)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    companion object {
        private const val TAG = "ClusterTuneHandoff"
        const val ODIN_SETTINGS_PACKAGE = "com.odin2.odinsettings"
        const val ODIN_SETTINGS_MAIN_ACTIVITY =
            "com.odin2.odinsettings.activity.MainSettingsActivity"
        const val SCRIPT_DIR_NAME = "ClusterScripts"
        const val SCRIPT_FILENAME = "clustertune-apply.sh"
    }
}
