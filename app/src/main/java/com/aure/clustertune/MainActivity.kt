package com.aure.clustertune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aure.clustertune.apps.AppProfileMonitorService
import com.aure.clustertune.overlay.OverlayPermission
import com.aure.clustertune.sleep.SleepProfileMonitorService
import com.aure.clustertune.tile.QuickSettingsTileAddResult
import com.aure.clustertune.tile.QuickSettingsTilePrompt
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.MainTunerScreen
import com.aure.clustertune.ui.SettingsScreen
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import com.aure.clustertune.update.AppRelease
import com.aure.clustertune.update.AppUpdateManager
import com.aure.clustertune.update.InstallLaunchResult
import com.aure.clustertune.update.UpdateCheckPolicy
import com.aure.clustertune.update.UpdateCheckResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }
    private val appUpdateManager by lazy { AppUpdateManager(this) }
    private val pendingUpdateRelease = mutableStateOf<AppRelease?>(null)
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
            privilegedExecutionResolver = container.privilegedExecutionResolver,
            installedAppRepository = container.installedAppRepository,
        )
    }
    private val exportProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(::exportProfilesToUri)
    }
    private val importProfilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(::importProfilesFromUri)
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.sleepProfileEnabled) {
                SleepProfileMonitorService.start(this@MainActivity)
            }
            maybeStartAppProfileMonitor()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestQuickSettingsTileOnFirstRun()
        maybeAutoDetectPrivilegedExecutionOnFirstRun()
        maybeCheckForUpdatesOnLaunch()
        maybeStartSleepProfileMonitor()
        maybeStartAppProfileMonitor()

        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    val launchableApps = viewModel.launchableApps.collectAsStateWithLifecycle().value
                    val recentActiveApps = viewModel.recentActiveApps.collectAsStateWithLifecycle().value
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var overlayPermissionRefresh by remember { mutableStateOf(0) }
                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                overlayPermissionRefresh++
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    val canDrawOverlays = remember(overlayPermissionRefresh) {
                        OverlayPermission.canDrawOverlays(this@MainActivity)
                    }

                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onBack = { showSettings = false },
                            onColorSourceChange = viewModel::setColorSource,
                            onAccentColorChange = viewModel::setAccentColor,
                            onCustomAccentColorChange = viewModel::setCustomAccentColor,
                            onDisplayFrequenciesAsPercentChange = viewModel::setDisplayFrequenciesAsPercent,
                            onTileTapBehaviorChange = { behavior ->
                                viewModel.setTileTapBehavior(behavior) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onApplyLastProfileOnBootChange = viewModel::setApplyLastProfileOnBoot,
                            sleepProfileOptions = state.displayProfiles,
                            onSleepProfileEnabledChange = { enabled ->
                                val profileId = settings.sleepProfileId
                                    ?: state.displayProfiles.firstOrNull()?.id
                                viewModel.configureSleepProfile(enabled, profileId) {
                                    if (enabled) {
                                        startSleepProfileMonitor()
                                    } else {
                                        SleepProfileMonitorService.stop(this@MainActivity)
                                    }
                                }
                            },
                            onSleepProfileChange = viewModel::setSleepProfile,
                            onResetProfiles = viewModel::resetProfilesToDefault,
                            onExportProfiles = {
                                exportProfilesLauncher.launch("clustertune-profiles.json")
                            },
                            onImportProfiles = {
                                importProfilesLauncher.launch(arrayOf("application/json", "text/*"))
                            },
                            onRequestAddQuickSettingsTile = {
                                requestQuickSettingsTile(showResultToast = true)
                            },
                            canRequestAddQuickSettingsTile = QuickSettingsTilePrompt.isSupported,
                            isQuickSettingsTileAdded = settings.isQuickSettingsTileAdded,
                            canDrawOverlays = canDrawOverlays,
                            onOpenOverlayPermissionSettings = {
                                startActivity(OverlayPermission.createSettingsIntent(this@MainActivity))
                            },
                            onCheckForUpdates = { checkForUpdates(showUpToDateToast = true) },
                            onAutomaticUpdateChecksEnabledChange = viewModel::setAutomaticUpdateChecksEnabled,
                            onUpdateCheckIntervalDaysChange = viewModel::setUpdateCheckIntervalDays,
                            onIncludePrereleaseUpdatesChange = viewModel::setIncludePrereleaseUpdates,
                            onProfileSwitchToastsEnabledChange = viewModel::setProfileSwitchToastsEnabled,
                            onProfileSwitchHistoryLimitChange = viewModel::setProfileSwitchHistoryLimit,
                            onPrivilegedExecutionMethodChange = viewModel::setPrivilegedExecutionMethod,
                            onAutoDetectPrivilegedExecutionMethod = viewModel::autoDetectPrivilegedExecutionMethod,
                        )
                    } else {
                        MainTunerScreen(
                            state = state,
                            displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                            sleepProfileId = settings.sleepProfileId.takeIf { settings.sleepProfileEnabled },
                            onApplyProfile = viewModel::applyProfile,
                            onApplyCurrent = { tunerState ->
                                viewModel.applyCurrent(tunerState) {
                                    QuickSettingsTileRefresher.requestUpdate(this@MainActivity)
                                }
                            },
                            onCreateProfile = viewModel::createUserProfile,
                            onUpdateProfile = viewModel::updateProfile,
                            onDeleteProfile = viewModel::deleteProfile,
                            onMoveProfile = viewModel::moveProfile,
                            launchableApps = launchableApps,
                            recentActiveApps = recentActiveApps,
                            onSaveAppProfileAssignment = { packageName, appLabel, profileId ->
                                viewModel.saveAppProfileAssignment(packageName, appLabel, profileId)
                                startAppProfileMonitor()
                            },
                            onDeleteAppProfileAssignment = viewModel::deleteAppProfileAssignment,
                            onRefreshInstalledApps = viewModel::refreshInstalledApps,
                            onOpenSettings = { showSettings = true },
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            onStatusMessageShown = viewModel::consumeStatusMessage,
                            onErrorMessageShown = viewModel::consumeErrorMessage,
                        )
                    }
                    pendingUpdateRelease.value?.let { release ->
                        UpdateAvailableDialog(
                            release = release,
                            onDismiss = { pendingUpdateRelease.value = null },
                            onInstall = {
                                pendingUpdateRelease.value = null
                                downloadAndInstallUpdate(release)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartSleepProfileMonitor()
        maybeStartAppProfileMonitor()
    }

    private fun startSleepProfileMonitor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        SleepProfileMonitorService.start(this)
    }

    private fun startAppProfileMonitor() {
        if (!AppProfileMonitorService.hasUsageStatsPermission(this)) {
            SingleToast.show(this, "Grant Usage Access to enable per-app profiles", Toast.LENGTH_LONG)
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        AppProfileMonitorService.start(this)
    }

    private fun maybeRequestQuickSettingsTileOnFirstRun() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.hasPromptedQuickSettingsTile) return@launch

            container.settingsStorage.persistQuickSettingsTilePromptShown()
            if (QuickSettingsTilePrompt.isSupported) {
                requestQuickSettingsTile(showResultToast = false)
            }
        }
    }

    private fun maybeAutoDetectPrivilegedExecutionOnFirstRun() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.privilegedExecutionMethodId != null) return@launch

            val methodId = container.privilegedExecutionResolver.autoDetectBestMethod(forceReprobe = true)
            container.settingsStorage.persistPrivilegedExecutionMethodId(methodId)
        }
    }

    private fun maybeStartSleepProfileMonitor() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (settings.sleepProfileEnabled) {
                startSleepProfileMonitor()
            }
        }
    }

    private fun maybeStartAppProfileMonitor() {
        lifecycleScope.launch {
            if (container.repository.observeState().first().appProfileAssignments.isNotEmpty() &&
                AppProfileMonitorService.hasUsageStatsPermission(this@MainActivity)
            ) {
                startAppProfileMonitor()
            }
        }
    }

    private fun requestQuickSettingsTile(showResultToast: Boolean) {
        QuickSettingsTilePrompt.request(this) { result ->
            if (result == QuickSettingsTileAddResult.ADDED || result == QuickSettingsTileAddResult.ALREADY_ADDED) {
                lifecycleScope.launch {
                    container.settingsStorage.persistQuickSettingsTileAdded(true)
                }
            }
            if (!showResultToast) return@request
            SingleToast.show(applicationContext, result.toToastMessage(), Toast.LENGTH_SHORT)
        }
    }

    private fun maybeCheckForUpdatesOnLaunch() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            val nowMillis = System.currentTimeMillis()
            if (!UpdateCheckPolicy.shouldCheck(
                    enabled = settings.automaticUpdateChecksEnabled,
                    intervalDays = settings.updateCheckIntervalDays,
                    lastCheckMillis = settings.lastUpdateCheckMillis,
                    nowMillis = nowMillis,
                )
            ) {
                return@launch
            }
            container.settingsStorage.persistLastUpdateCheckMillis(nowMillis)
            checkForUpdates(showUpToDateToast = false)
        }
    }

    private fun checkForUpdates(showUpToDateToast: Boolean) {
        lifecycleScope.launch {
            if (showUpToDateToast) {
                SingleToast.show(applicationContext, "Checking for updates…", Toast.LENGTH_SHORT)
            }
            container.settingsStorage.persistLastUpdateCheckMillis(System.currentTimeMillis())
            val settings = container.settingsStorage.settings.first()
            appUpdateManager.checkForUpdates(includePrereleases = settings.includePrereleaseUpdates)
                .onSuccess { result ->
                    when (result) {
                        is UpdateCheckResult.UpToDate -> {
                            if (showUpToDateToast) {
                                SingleToast.show(
                                    applicationContext,
                                    "ClusterTune is up to date (${result.currentVersionName})",
                                    Toast.LENGTH_LONG,
                                )
                            }
                        }

                        is UpdateCheckResult.UpdateAvailable -> {
                            pendingUpdateRelease.value = result.release
                        }
                    }
                }
                .onFailure { throwable ->
                    SingleToast.show(
                        applicationContext,
                        throwable.message ?: "Failed to check for updates",
                        Toast.LENGTH_LONG,
                    )
                }
        }
    }

    private fun downloadAndInstallUpdate(release: AppRelease) {
        lifecycleScope.launch {
            SingleToast.show(applicationContext, "Downloading ${release.tagName}…", Toast.LENGTH_SHORT)
            appUpdateManager.downloadApk(release)
                .onSuccess { apkFile ->
                    when (appUpdateManager.installApk(apkFile)) {
                        InstallLaunchResult.Started -> SingleToast.show(
                            applicationContext,
                            "Opening installer for ${release.tagName}",
                            Toast.LENGTH_LONG,
                        )

                        InstallLaunchResult.PermissionRequired -> SingleToast.show(
                            applicationContext,
                            "Allow ClusterTune to install unknown apps, then check again.",
                            Toast.LENGTH_LONG,
                        )
                    }
                }
                .onFailure { throwable ->
                    SingleToast.show(
                        applicationContext,
                        throwable.message ?: "Failed to download update",
                        Toast.LENGTH_LONG,
                    )
                }
        }
    }

    private fun exportProfilesToUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = viewModel.exportProfilesJson()
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                } ?: error("Unable to open export file")
            }.onSuccess {
                SingleToast.show(applicationContext, "Exported profiles", Toast.LENGTH_SHORT)
            }.onFailure { throwable ->
                SingleToast.show(
                    applicationContext,
                    throwable.message ?: "Failed to export profiles",
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun importProfilesFromUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Unable to open import file")
                viewModel.importProfilesJson(json)
            }.onSuccess { importedCount ->
                SingleToast.show(applicationContext, "Imported $importedCount profiles", Toast.LENGTH_SHORT)
            }.onFailure { throwable ->
                SingleToast.show(
                    applicationContext,
                    throwable.message ?: "Failed to import profiles",
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    private fun QuickSettingsTileAddResult.toToastMessage(): String {
        return when (this) {
            QuickSettingsTileAddResult.ADDED -> "Quick Settings tile added"
            QuickSettingsTileAddResult.ALREADY_ADDED -> "Quick Settings tile is already added"
            QuickSettingsTileAddResult.NOT_ADDED -> "Quick Settings tile was not added"
            QuickSettingsTileAddResult.UNAVAILABLE -> "Quick Settings tile prompt is unavailable on this device"
            QuickSettingsTileAddResult.ERROR -> "Failed to request Quick Settings tile"
        }
    }

}

@Composable
private fun UpdateAvailableDialog(
    release: AppRelease,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update ${release.tagName} available") },
        text = {
            Text(
                text = release.body
                    ?.takeIf { it.isNotBlank() }
                    ?.take(2_000)
                    ?: "No changelog was provided for this release.",
            )
        },
        confirmButton = {
            TextButton(onClick = onInstall) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now")
            }
        },
    )
}

