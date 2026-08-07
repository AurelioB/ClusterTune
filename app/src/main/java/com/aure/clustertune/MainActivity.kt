package com.aure.clustertune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aure.clustertune.apps.AppProfileMonitorService
import com.aure.clustertune.overlay.OverlayHostService
import com.aure.clustertune.overlay.OverlayPermission
import com.aure.clustertune.permissions.AppAccess
import com.aure.clustertune.permissions.AppAccessStatus
import com.aure.clustertune.permissions.missingAppAccess
import com.aure.clustertune.sleep.SleepProfileMonitorService
import com.aure.clustertune.tile.QuickSettingsTileAddResult
import com.aure.clustertune.tile.QuickSettingsTilePrompt
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.MainTunerScreen
import com.aure.clustertune.ui.PermissionCheckDialog
import com.aure.clustertune.ui.SettingsScreen
import com.aure.clustertune.ui.WirelessDebugSetupScreen
import com.aure.clustertune.ui.SupportScreen
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneSystemBars
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

    /**
     * Set when a previously-working wireless-debugging connection is found dead
     * on resume, so the main screen can tell the user what happened and offer to
     * reopen setup (rather than silently showing profiles that can't be applied).
     */
    private val wirelessConnectionLost = mutableStateOf(false)
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
    ) { granted ->
        if (granted) {
            lifecycleScope.launch {
                val settings = container.settingsStorage.settings.first()
                if (settings.sleepProfileEnabled) {
                    SleepProfileMonitorService.start(this@MainActivity)
                }
                maybeStartAppProfileMonitor()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeAutoDetectPrivilegedExecutionOnFirstRun()
        maybeCheckForUpdatesOnLaunch()
        maybeStartSleepProfileMonitor()
        maybeStartAppProfileMonitor()

        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                ClusterTuneSystemBars()
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    val launchableApps = viewModel.launchableApps.collectAsStateWithLifecycle().value
                    val recentActiveApps = viewModel.recentActiveApps.collectAsStateWithLifecycle().value
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    var showSupport by rememberSaveable { mutableStateOf(false) }
                    var showWirelessSetup by rememberSaveable { mutableStateOf(false) }
                    BackHandler(enabled = showSettings || showSupport || showWirelessSetup) {
                        showSettings = false
                        showSupport = false
                        if (showWirelessSetup) {
                            showWirelessSetup = false
                            // Connecting there changes availability; re-probe.
                            viewModel.recheckExecutionAvailability()
                        }
                    }

                    // Wireless-debug connect state surfaced on the main menu so a
                    // device already paired this boot can reconnect without opening
                    // the setup screen.
                    val cm = container.wirelessDebugConnectionManager
                    var wirelessConnectStatus by remember {
                        mutableStateOf(
                            if (cm.connectionInfo != null) "Connected. Ready to apply profiles." else "Not connected",
                        )
                    }
                    var isWirelessDebugConnected by remember { mutableStateOf(cm.connectionInfo != null) }
                    val onConnectWirelessDebug: () -> Unit = {
                        wirelessConnectStatus = "Looking for wireless debugging…"
                        // Try mDNS discovery first; if it doesn't resolve within a
                        // few seconds, fall back to the port scan (the reliable path
                        // on some networks).
                        cm.startConnectDiscovery(
                            onConnected = { info ->
                                isWirelessDebugConnected = true
                                wirelessConnectStatus =
                                    "Connected (${info.host}:${info.port}). Ready to apply profiles."
                                viewModel.recheckExecutionAvailability()
                            },
                            onUnavailable = {
                                wirelessConnectStatus =
                                    "Wireless debugging not found. Make sure it's ON, then use Set up to pair."
                            },
                        )
                        lifecycleScope.launch {
                            var waited = 0
                            while (waited < 3000 && !isWirelessDebugConnected) {
                                kotlinx.coroutines.delay(500)
                                waited += 500
                            }
                            if (!isWirelessDebugConnected) {
                                wirelessConnectStatus = "mDNS didn't respond; scanning directly…"
                                cm.scanForConnectPort { info ->
                                    if (info != null) {
                                        isWirelessDebugConnected = true
                                        wirelessConnectStatus =
                                            "Connected (${info.host}:${info.port}). Ready to apply profiles."
                                        viewModel.recheckExecutionAvailability()
                                    } else {
                                        wirelessConnectStatus =
                                            "Couldn't connect. Make sure Wireless debugging is ON, or use Set up to pair."
                                    }
                                }
                            }
                        }
                    }

                    var permissionRefresh by remember { mutableStateOf(0) }
                    DisposableEffect(Unit) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                permissionRefresh++
                            }
                        }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    val canDrawOverlays = remember(permissionRefresh) {
                        OverlayPermission.canDrawOverlays(this@MainActivity)
                    }
                    val hasUsageAccess = remember(permissionRefresh) {
                        AppProfileMonitorService.hasUsageStatsPermission(this@MainActivity)
                    }
                    val hasNotificationAccess = remember(permissionRefresh) {
                        NotificationManagerCompat.from(this@MainActivity)
                            .areNotificationsEnabled()
                    }
                    val canInstallUpdates = remember(permissionRefresh) {
                        packageManager.canRequestPackageInstalls()
                    }
                    val missingAccess = missingAppAccess(
                        AppAccessStatus(
                            overlayGranted = canDrawOverlays,
                            usageGranted = hasUsageAccess,
                            notificationsGranted = hasNotificationAccess,
                        ),
                    )
                    var showPermissionDialog by rememberSaveable { mutableStateOf(true) }
                    val permissionDialogVisible = showPermissionDialog && missingAccess.isNotEmpty()
                    LaunchedEffect(permissionDialogVisible) {
                        if (!permissionDialogVisible) {
                            maybeRequestQuickSettingsTileOnFirstRun()
                        }
                    }

                    // Wireless-debugging link dropped (e.g. the user turned
                    // wireless debugging off, or it reset on reboot). Say so
                    // plainly and offer to reopen setup, instead of leaving a
                    // profile list that silently fails to apply.
                    if (wirelessConnectionLost.value) {
                        AlertDialog(
                            onDismissRequest = { wirelessConnectionLost.value = false },
                            title = { Text("Wireless debugging disconnected") },
                            text = {
                                Text(
                                    "ClusterTune can no longer reach Android's Wireless debugging, " +
                                        "so profiles can't be applied. Check that Wireless debugging " +
                                        "is still switched on, then pair again.",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    wirelessConnectionLost.value = false
                                    showSettings = false
                                    showSupport = false
                                    showWirelessSetup = true
                                }) { Text("Set up") }
                            },
                            dismissButton = {
                                TextButton(onClick = { wirelessConnectionLost.value = false }) {
                                    Text("Dismiss")
                                }
                            },
                        )
                    }

                    if (showWirelessSetup) {
                        WirelessDebugSetupScreen(
                            connectionManager = container.wirelessDebugConnectionManager,
                            onBack = {
                                showWirelessSetup = false
                                // Connecting there changes availability; re-probe.
                                viewModel.recheckExecutionAvailability()
                            },
                        )
                    } else if (showSettings) {
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
                            canDrawOverlays = canDrawOverlays,
                            onOpenOverlayPermissionSettings = {
                                startActivity(OverlayPermission.createSettingsIntent(this@MainActivity))
                            },
                            hasUsageAccess = hasUsageAccess,
                            onOpenUsageAccessSettings = {
                                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            },
                            hasNotificationAccess = hasNotificationAccess,
                            onOpenNotificationSettings = {
                                startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                    },
                                )
                            },
                            canInstallUpdates = canInstallUpdates,
                            onOpenInstallPermissionSettings = {
                                startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = Uri.parse("package:$packageName")
                                    },
                                )
                            },
                            onLeftEdgeProfilePickerEnabledChange = { enabled ->
                                viewModel.setLeftEdgeProfilePickerEnabled(enabled) {
                                    when {
                                        !enabled -> OverlayHostService.hideEdgeHandle(this@MainActivity)
                                        !canDrawOverlays -> {
                                            SingleToast.show(
                                                this@MainActivity,
                                                "Grant overlay permission to use the edge picker",
                                                Toast.LENGTH_LONG,
                                            )
                                            startActivity(
                                                OverlayPermission.createSettingsIntent(this@MainActivity),
                                            )
                                        }
                                        !hasUsageAccess -> {
                                            SingleToast.show(
                                                this@MainActivity,
                                                "Grant Usage Access to identify the current app",
                                                Toast.LENGTH_LONG,
                                            )
                                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                        else -> OverlayHostService.showEdgeHandle(this@MainActivity)
                                    }
                                }
                            },
                            onEdgeHandlePreview = { height, thickness, position, opacity ->
                                OverlayHostService.previewEdgeHandle(
                                    context = this@MainActivity,
                                    heightDp = height,
                                    thicknessDp = thickness,
                                    verticalPositionPercent = position,
                                    opacityPercent = opacity,
                                )
                            },
                            onEdgeHandleHeightChange = viewModel::setEdgeHandleHeightDp,
                            onEdgeHandleThicknessChange = viewModel::setEdgeHandleThicknessDp,
                            onEdgeHandleVerticalPositionChange = viewModel::setEdgeHandleVerticalPositionPercent,
                            onEdgeHandleOpacityChange = viewModel::setEdgeHandleOpacityPercent,
                            onCheckForUpdates = { checkForUpdates(showUpToDateToast = true) },
                            onAutomaticUpdateChecksEnabledChange = viewModel::setAutomaticUpdateChecksEnabled,
                            onUpdateCheckIntervalDaysChange = viewModel::setUpdateCheckIntervalDays,
                            onIncludePrereleaseUpdatesChange = viewModel::setIncludePrereleaseUpdates,
                            onProfileSwitchToastsEnabledChange = viewModel::setProfileSwitchToastsEnabled,
                            onProfileSwitchHistoryLimitChange = viewModel::setProfileSwitchHistoryLimit,
                            onPrivilegedExecutionMethodChange = viewModel::setPrivilegedExecutionMethod,
                            onAutoDetectPrivilegedExecutionMethod = viewModel::autoDetectPrivilegedExecutionMethod,
                            onOpenWirelessDebugSetup = {
                                showSettings = false
                                showWirelessSetup = true
                            },
                        )
                    } else if (showSupport) {
                        SupportScreen(onBack = { showSupport = false })
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
                            onSaveAppProfileAssignment = { packageName, appLabel, profileId, customMaxFrequencies ->
                                viewModel.saveAppProfileAssignment(
                                    packageName = packageName,
                                    appLabel = appLabel,
                                    profileId = profileId,
                                    customMaxFrequencies = customMaxFrequencies,
                                )
                                startAppProfileMonitor()
                            },
                            onDeleteAppProfileAssignment = viewModel::deleteAppProfileAssignment,
                            onRefreshInstalledApps = viewModel::refreshInstalledApps,
                            onOpenSettings = { showSettings = true },
                            onOpenSupport = { showSupport = true },
                            onOpenWirelessDebugSetup = { showWirelessSetup = true },
                            onConnectWirelessDebug = onConnectWirelessDebug,
                            wirelessConnectStatus = wirelessConnectStatus,
                            isWirelessDebugConnected = isWirelessDebugConnected,
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            onStatusMessageShown = viewModel::consumeStatusMessage,
                            onErrorMessageShown = viewModel::consumeErrorMessage,
                        )
                    }
                    if (permissionDialogVisible) {
                        PermissionCheckDialog(
                            missingAccess = missingAccess,
                            onFixAccess = { access ->
                                when (access) {
                                    AppAccess.OVERLAY -> {
                                        startActivity(OverlayPermission.createSettingsIntent(this@MainActivity))
                                    }

                                    AppAccess.USAGE -> {
                                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                    }

                                    AppAccess.NOTIFICATIONS -> {
                                        startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                            },
                                        )
                                    }
                                }
                            },
                            onDismiss = { showPermissionDialog = false },
                        )
                    } else {
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
    }

    override fun onResume() {
        super.onResume()
        // If we believed we were connected, confirm it. verifyConnection() clears
        // a dead connection, so this both updates availability and lets us tell
        // the user their wireless-debugging link dropped.
        lifecycleScope.launch {
            val cm = container.wirelessDebugConnectionManager
            if (cm.connectionInfo != null) {
                val alive = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cm.verifyConnection()
                }
                if (!alive) {
                    wirelessConnectionLost.value = true
                }
            }
            viewModel.recheckExecutionAvailability()
        }
        maybeStartSleepProfileMonitor()
        maybeStartAppProfileMonitor()
        maybeStartLeftEdgeProfilePicker()
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
            if (settings.sleepProfileEnabled && hasNotificationAccess()) {
                startSleepProfileMonitor()
            }
        }
    }

    private fun maybeStartAppProfileMonitor() {
        lifecycleScope.launch {
            if (container.repository.observeState().first().appProfileAssignments.isNotEmpty() &&
                AppProfileMonitorService.hasUsageStatsPermission(this@MainActivity) &&
                hasNotificationAccess()
            ) {
                startAppProfileMonitor()
            }
        }
    }

    private fun hasNotificationAccess(): Boolean =
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun maybeStartLeftEdgeProfilePicker() {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (
                settings.leftEdgeProfilePickerEnabled &&
                OverlayPermission.canDrawOverlays(this@MainActivity) &&
                AppProfileMonitorService.hasUsageStatsPermission(this@MainActivity)
            ) {
                OverlayHostService.showEdgeHandle(this@MainActivity)
            } else if (settings.leftEdgeProfilePickerEnabled) {
                OverlayHostService.hideEdgeHandle(this@MainActivity)
            }
        }
    }

    private fun requestQuickSettingsTile(showResultToast: Boolean) {
        QuickSettingsTilePrompt.request(this) { result ->
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
