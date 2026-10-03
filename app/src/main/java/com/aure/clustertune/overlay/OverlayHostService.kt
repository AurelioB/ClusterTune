package com.aure.clustertune.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.doOnLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.aure.clustertune.AppContainer
import com.aure.clustertune.MainActivity
import com.aure.clustertune.R
import com.aure.clustertune.autotune.AdaptiveTuneRuntime
import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.ForegroundAppResolver
import com.aure.clustertune.apps.TRANSIENT_APP_WINDOW_PACKAGES
import com.aure.clustertune.apps.VENDOR_GAME_ASSISTANT_PACKAGES
import com.aure.clustertune.apps.VisibleAppSnapshot
import com.aure.clustertune.apps.VisibleAppWindow
import com.aure.clustertune.apps.VisibleAppWindowEvents
import com.aure.clustertune.apps.nominalDisplayRefreshRateFps
import com.aure.clustertune.apps.selectVisibleAppWindow
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.notifications.AppForegroundNotification
import com.aure.clustertune.permissions.AppProfileAccessibilityAccess
import com.aure.clustertune.quicktuner.PerformanceQuickTunerApplyRepository
import com.aure.clustertune.quicktuner.QuickTunerApplyHandler
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.CompactOverlayMode
import com.aure.clustertune.ui.CompactOverlayScreen
import com.aure.clustertune.ui.PerformanceHudOverlay
import com.aure.clustertune.ui.PerformanceHudUiModel
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import com.aure.clustertune.ui.designsystem.component.CtCompactOverlayFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import com.aure.clustertune.data.retryTransientReads
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EdgeHandleAppearance(
    val heightDp: Int,
    val thicknessDp: Int,
    val verticalPositionPercent: Int,
    val opacityPercent: Int,
)

private data class PerformanceHudSamplingContext(
    val foreground: ForegroundAppInfo?,
    val autoTuneActive: Boolean,
    val autoTunePackageName: String?,
)

internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"

internal fun compactProfilePickerExcludedPackages(ownPackageName: String): Set<String> =
    TRANSIENT_APP_WINDOW_PACKAGES + VENDOR_GAME_ASSISTANT_PACKAGES + ownPackageName

internal fun hasFilteredCompactProfilePickerWindow(
    snapshot: VisibleAppSnapshot,
    targetDisplayId: Int,
    filteredPackages: Set<String>,
): Boolean = snapshot.windowsByDisplay[targetDisplayId]
    .orEmpty()
    .any { window -> window.packageName in filteredPackages }

/**
 * Resolves a real visible app, or consumes the latest picker-only app event.
 * The synthetic window exists only in this returned copy and is never published
 * to the app-profile coordinator.
 */
internal fun compactProfilePickerTargetSnapshot(
    snapshot: VisibleAppSnapshot,
    targetDisplayId: Int,
    excludedPackages: Set<String>,
): VisibleAppSnapshot? {
    if (!snapshot.isInteractive) return null
    val selected = selectVisibleAppWindow(snapshot, targetDisplayId, excludedPackages)
    val pickerPackage = snapshot.pickerPackageByDisplay[targetDisplayId]?.takeIf { candidate ->
        candidate.isNotBlank() &&
            candidate !in excludedPackages &&
            targetDisplayId in snapshot.refreshRateFpsByDisplay
    }
    if (pickerPackage == null) return snapshot.takeIf { selected != null }
    if (selected?.packageName == pickerPackage) return snapshot
    val syntheticWindow = VisibleAppWindow(
        packageName = pickerPackage,
        displayId = targetDisplayId,
        isFocused = true,
        isActive = true,
    )
    return snapshot.copy(
        windowsByDisplay = snapshot.windowsByDisplay +
            (targetDisplayId to listOf(syntheticWindow)),
        recentPackageByDisplay = snapshot.recentPackageByDisplay +
            (targetDisplayId to pickerPackage),
    )
}

internal data class CompactProfilePickerSnapshotObservation(
    val targetSnapshot: VisibleAppSnapshot?,
    val packageName: String?,
    val isObscured: Boolean,
)

internal fun observeCompactProfilePickerSnapshot(
    snapshot: VisibleAppSnapshot,
    targetDisplayId: Int,
    excludedPackages: Set<String>,
    pickerShowing: Boolean,
    preferredPackageName: String? = null,
): CompactProfilePickerSnapshotObservation {
    val target = compactProfilePickerTargetSnapshot(
        snapshot = snapshot,
        targetDisplayId = targetDisplayId,
        excludedPackages = excludedPackages,
    )
    val selectedPackageName = target?.let { targetSnapshot ->
        selectVisibleAppWindow(
            snapshot = targetSnapshot,
            targetDisplayId = targetDisplayId,
            excludedPackages = excludedPackages,
            preferredPackageName = preferredPackageName,
        )?.packageName
    }
    val selectedIsEvidenceBacked = selectedPackageName != null &&
        selectedPackageName == snapshot.pickerPackageByDisplay[targetDisplayId] &&
        selectVisibleAppWindow(snapshot, targetDisplayId, excludedPackages) == null
    val targetWindows = snapshot.windowsByDisplay[targetDisplayId].orEmpty()
    val emptyExistingDisplay = targetWindows.isEmpty() &&
        targetDisplayId in snapshot.refreshRateFpsByDisplay
    val hasFilteredWindow = hasFilteredCompactProfilePickerWindow(
        snapshot = snapshot,
        targetDisplayId = targetDisplayId,
        filteredPackages = excludedPackages,
    )
    return CompactProfilePickerSnapshotObservation(
        targetSnapshot = target,
        packageName = selectedPackageName,
        isObscured = pickerShowing && snapshot.isInteractive &&
            (selectedIsEvidenceBacked || hasFilteredWindow || emptyExistingDisplay),
    )
}

internal fun updateCompactProfilePickerForeground(
    current: ForegroundAppInfo?,
    detected: ForegroundAppInfo?,
    ignoredPackages: Set<String> = emptySet(),
    hasFilteredVisibleWindow: Boolean = false,
): ForegroundAppInfo? {
    val verified = detected?.takeUnless { it.packageName in ignoredPackages }
    if (verified != null) return verified
    val filteredOnly = hasFilteredVisibleWindow || detected != null
    return current.takeIf { filteredOnly }
}

internal fun isCompactProfilePickerMutationContextCurrent(
    capturedPackageName: String?,
    currentPackageName: String?,
    capturedSessionToken: Long,
    currentSessionToken: Long,
): Boolean = capturedPackageName != null &&
    capturedPackageName == currentPackageName &&
    capturedSessionToken == currentSessionToken

internal suspend fun runCompactProfilePickerMutationIfCurrent(
    capturedForeground: ForegroundAppInfo?,
    ensureCurrent: () -> Boolean,
    mutation: suspend () -> Unit,
): Boolean {
    if (capturedForeground != null && !ensureCurrent()) return false
    mutation()
    return true
}

internal fun initialCompactOverlayMode(
    requestedMode: CompactOverlayMode,
    foregroundPackageName: String?,
    assignments: List<AppProfileAssignment>,
): CompactOverlayMode {
    if (requestedMode != CompactOverlayMode.PROFILES || foregroundPackageName == null) {
        return requestedMode
    }
    return if (assignments.any { assignment ->
            assignment.packageName == foregroundPackageName && assignment.isAutoTune
        }
    ) {
        CompactOverlayMode.AUTO_TUNE
    } else {
        CompactOverlayMode.PROFILES
    }
}

internal fun correctedCompactOverlayMode(
    requestedMode: CompactOverlayMode,
    currentMode: CompactOverlayMode,
    foregroundPackageName: String?,
    assignments: List<AppProfileAssignment>,
    modeChangedByUser: Boolean,
): CompactOverlayMode = if (modeChangedByUser) {
    currentMode
} else {
    initialCompactOverlayMode(requestedMode, foregroundPackageName, assignments)
}

class OverlayHostService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    private val foregroundIgnoredPackages by lazy { compactProfilePickerExcludedPackages(packageName) }
    private val foregroundExcludedPackages by lazy { foregroundIgnoredPackages }

    override val viewModelStore = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val container by lazy { AppContainer(this) }
    private val windowController by lazy { OverlayWindowController(this) }
    private val viewModel by lazy {
        ViewModelProvider(
            this,
            TunerViewModel.factory(
                repository = container.repository,
                settingsStorage = container.settingsStorage,
                privilegedExecutionResolver = container.privilegedExecutionResolver,
                installedAppRepository = container.installedAppRepository,
            ),
        )[TunerViewModel::class.java]
    }
    private var screenReceiverRegistered = false
    private var keepEdgeHandle = false
    private val foregroundAppResolver by lazy { ForegroundAppResolver(this) }
    private var compactProfilePickerSessionJob: Job? = null
    private var compactProfilePickerSessionToken = 0L
    private var compactAssignmentMutationJob: Job? = null
    private var compactOverlayModeChangedByUser = false
    private val compactOverlayMode = MutableStateFlow(CompactOverlayMode.PROFILES)
    private val compactProfilePickerForeground = MutableStateFlow<ForegroundAppInfo?>(null)
    private val edgeHandleAppearance = MutableStateFlow<EdgeHandleAppearance?>(null)
    private val performanceHudModel = MutableStateFlow(PerformanceHudUiModel())
    private val performanceHudForeground = MutableStateFlow<ForegroundAppInfo?>(null)
    private val performanceHudTelemetry = MutableStateFlow(PerformanceHudTelemetryState())
    private val performanceHudGraphHistory = MutableStateFlow(PerformanceHudGraphHistory())
    private val performanceHudVendorState = MutableStateFlow(PerformanceHudVendorState())
    private val performanceHudRefreshRateFps = MutableStateFlow<Int?>(null)
    private var performanceHudSessionJob: Job? = null
    private val performanceHudVendorReader by lazy { PerformanceHudVendorStateReader(this) }

    // OverlayWindowController uses applicationContext's WindowManager, which
    // renders on the default display. Service contexts may be non-visual and
    // throw from getDisplay(), so keep this explicit.
    private val overlayDisplayId = Display.DEFAULT_DISPLAY

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                dismissOverlay()
                hidePerformanceHud()
            }
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        AppForegroundNotification.start(this)
        registerScreenReceiver()
        lifecycleScope.launch {
            container.settingsStorage.settings
                .map { it.autoTuneEnabled }
                .distinctUntilChanged()
                .retryTransientReads { error ->
                    Log.w(TAG, "HUD settings unavailable; closing HUD before retry", error)
                    if (windowController.isPerformanceHudShowing) hidePerformanceHud()
                }
                .catch { error ->
                    Log.e(TAG, "HUD settings observer stopped", error)
                    if (windowController.isPerformanceHudShowing) hidePerformanceHud()
                }
                .collect { enabled ->
                    if (!enabled && windowController.isPerformanceHudShowing) hidePerformanceHud()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_COMPACT_TUNER -> showCompactTunerOverlay()
            ACTION_SHOW_PROFILE_PICKER -> openProfilePickerForForegroundApp()
            ACTION_SHOW_EDGE_HANDLE -> showEdgeHandleIfEnabled()
            ACTION_TOGGLE_PERFORMANCE_HUD -> togglePerformanceHud()
            ACTION_PREVIEW_EDGE_HANDLE -> previewEdgeHandle(intent)
            ACTION_HIDE_EDGE_HANDLE -> hideEdgeHandle()
            ACTION_DISMISS -> dismissOverlay(intent.overlayTypeExtra())
            else -> showEdgeHandleIfEnabled()
        }
        return if (keepEdgeHandle || intent?.action == ACTION_SHOW_EDGE_HANDLE || intent == null) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        cancelCompactProfilePickerSession()
        performanceHudSessionJob?.cancel()
        performanceHudSessionJob = null
        performanceHudForeground.value = null
        performanceHudTelemetry.value = PerformanceHudTelemetryState()
        performanceHudGraphHistory.value = PerformanceHudGraphHistory()
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        windowController.dismissAll()
        viewModelStore.clear()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        performanceHudRefreshRateFps.value = currentPerformanceHudRefreshRateFps()
        windowController.refreshLayouts()
    }

    private fun showCompactTunerOverlay() {
        startCompactProfilePickerSession(CompactOverlayMode.TUNER)
    }

    private fun togglePerformanceHud() {
        lifecycleScope.launch {
            if (!isPerformanceHudEnabled()) {
                hidePerformanceHud()
                return@launch
            }
            if (windowController.isPerformanceHudShowing) {
                hidePerformanceHud()
            } else {
                showPerformanceHud()
            }
        }
    }

    private suspend fun isPerformanceHudEnabled(): Boolean = try {
        container.settingsStorage.settings.first().autoTuneEnabled
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.w(TAG, "Unable to check experimental HUD setting", error)
        false
    }

    private fun showPerformanceHud() {
        if (!OverlayPermission.canDrawOverlays(this)) {
            SingleToast.show(this, "Grant overlay permission to show the performance HUD", Toast.LENGTH_LONG)
            stopIfIdle()
            return
        }
        performanceHudRefreshRateFps.value = currentPerformanceHudRefreshRateFps()
        val view = OverlayComposeViewFactory.create(this, this, this, this) {
            val settings by container.settingsStorage.settings.collectAsStateWithLifecycle(
                initialValue = AppSettings(),
            )
            val model by performanceHudModel.collectAsStateWithLifecycle()
            ClusterTuneTheme(settings = settings) {
                PerformanceHudOverlay(
                    model = model,
                    onAutoTuneTargetCommit = ::commitPerformanceHudTarget,
                )
            }
        }
        runCatching { windowController.showPerformanceHud(view) }
            .onSuccess { startPerformanceHudSession() }
            .onFailure { throwable ->
                Log.e(TAG, "Failed to show performance HUD", throwable)
                windowController.removePerformanceHud()
                stopIfIdle()
            }
    }

    private fun hidePerformanceHud() {
        val cleanupJob = performanceHudSessionJob
        cleanupJob?.cancel()
        performanceHudSessionJob = null
        performanceHudModel.value = PerformanceHudUiModel()
        performanceHudGraphHistory.value = PerformanceHudGraphHistory()
        windowController.removePerformanceHud()
        QuickSettingsTileRefresher.requestUpdate(applicationContext)
        if (cleanupJob == null) {
            stopIfIdle()
        } else {
            lifecycleScope.launch {
                cleanupJob.join()
                stopIfIdle()
            }
        }
    }

    /** Filled by the telemetry integration below; kept synchronous so QS toggles attach instantly. */
    private fun startPerformanceHudSession() {
        performanceHudSessionJob?.cancel()
        performanceHudGraphHistory.value = PerformanceHudGraphHistory()
        performanceHudSessionJob = lifecycleScope.launch {
            val graphAccumulator = PerformanceHudGraphAccumulator()
            coroutineScope {
                launch {
                    while (true) {
                        performanceHudRefreshRateFps.value = currentPerformanceHudRefreshRateFps()
                        performanceHudVendorState.value = withContext(Dispatchers.IO) {
                            performanceHudVendorReader.read()
                        }
                        delay(PERFORMANCE_HUD_SAMPLE_INTERVAL_MS)
                    }
                }
                launch {
                    AdaptiveTuneRuntime.state.collect { runtime ->
                        val source = runtime.performanceHudGraphSource() ?: return@collect
                        val sampleIdentity = runtime.sampleTimestampNanos ?: return@collect
                        performanceHudGraphHistory.value = graphAccumulator.record(
                            source = source,
                            sampleIdentity = sampleIdentity,
                            framesPerSecond = runtime.performanceHudFramesPerSecond(),
                            cpuLoadPercent = runtime.performanceHudCpuLoadPercent(),
                            gpuBusyPercent = runtime.performanceHudGpuBusyPercent(),
                        )
                    }
                }
                launch {
                    val profileContext = combine(
                        container.profileStorage.effectiveProfileState,
                        container.profileStorage.appProfileAssignments,
                    ) { effective, assignments -> effective to assignments }
                    val environment = combine(
                        performanceHudVendorState,
                        performanceHudRefreshRateFps,
                        performanceHudGraphHistory,
                    ) { vendor, refreshRateFps, graphHistory ->
                        Triple(vendor, refreshRateFps, graphHistory)
                    }
                    combine(
                        performanceHudTelemetry,
                        AdaptiveTuneRuntime.state,
                        performanceHudForeground,
                        environment,
                        profileContext,
                    ) { telemetry, runtime, foreground, environmentState, (effective, assignments) ->
                        val (vendor, refreshRateFps, graphHistory) = environmentState
                        val packageName = runtime.packageName.takeIf { runtime.active }
                            ?: foreground?.packageName
                        val configuredTarget = packageName?.let { currentPackage ->
                            assignments.firstOrNull { assignment ->
                                assignment.packageName == currentPackage && assignment.isAutoTune
                            }?.autoTuneTargetFps
                        }
                        val expectedGraphSource = if (runtime.active) {
                            runtime.performanceHudGraphSource()
                        } else {
                            telemetry.performanceHudGraphSource(packageName)
                        }
                        val displayedGraphHistory = graphHistory.takeIf {
                            expectedGraphSource != null && it.source == expectedGraphSource
                        } ?: PerformanceHudGraphHistory()
                        buildPerformanceHudUiModel(
                            telemetry = telemetry.sample,
                            autoTune = runtime,
                            autoTunePackageName = packageName,
                            configuredAutoTuneTargetFps = configuredTarget,
                            displayRefreshRateFps = refreshRateFps,
                            effectiveProfileName = effective?.name,
                            vendorState = vendor,
                            graphHistory = displayedGraphHistory,
                        )
                    }.collect(performanceHudModel::emit)
                }
                launch {
                    combine(
                        VisibleAppWindowEvents.snapshots,
                        AdaptiveTuneRuntime.state,
                    ) { snapshot, runtime ->
                        val preferredPackageName = performanceHudForeground.value?.packageName
                        val observation = observeCompactProfilePickerSnapshot(
                            snapshot = snapshot,
                            targetDisplayId = overlayDisplayId,
                            excludedPackages = foregroundExcludedPackages,
                            pickerShowing = windowController.isPerformanceHudShowing,
                            preferredPackageName = preferredPackageName,
                        )
                        val detected = observation.targetSnapshot?.let { targetSnapshot ->
                            foregroundAppResolver.resolve(
                                snapshot = targetSnapshot,
                                targetDisplayId = overlayDisplayId,
                                excludedPackages = foregroundExcludedPackages,
                                preferredPackageName = preferredPackageName,
                            )
                        }
                        val updated = updateCompactProfilePickerForeground(
                            current = performanceHudForeground.value,
                            detected = detected,
                            ignoredPackages = foregroundIgnoredPackages,
                            hasFilteredVisibleWindow = observation.isObscured,
                        )
                        performanceHudForeground.value = updated
                        PerformanceHudSamplingContext(
                            foreground = updated,
                            autoTuneActive = runtime.active,
                            autoTunePackageName = runtime.packageName,
                        )
                    }.distinctUntilChangedBy { context ->
                        listOf(
                            context.foreground?.packageName,
                            context.foreground?.currentRefreshRateFps?.toString(),
                            context.autoTuneActive.toString(),
                            context.autoTunePackageName,
                        )
                    }.collectLatest { context ->
                        performanceHudTelemetry.value = PerformanceHudTelemetryState()
                        if (context.autoTuneActive) {
                            // Auto Tune already owns a live telemetry source and publishes the same
                            // presentation metrics through AdaptiveTuneRuntime.
                            awaitCancellation()
                        }
                        val foreground = context.foreground
                        val target = PerformanceHudTelemetryTarget(
                            packageName = foreground?.packageName,
                            targetFps = foreground?.let {
                                performanceHudRefreshRateFps.value?.takeIf { fps -> fps > 0 }
                                    ?: it.currentRefreshRateFps?.takeIf { fps -> fps > 0 }
                                    ?: 60
                            } ?: 0,
                        )
                        while (true) {
                            runCatching {
                                PerformanceHudTelemetryRunner(
                                    backend = ClusterTunePerformanceHudTelemetryBackend(container.hostClient),
                                    sampleIntervalMillis = PERFORMANCE_HUD_SAMPLE_INTERVAL_MS,
                                ).run(target) { state ->
                                    val sample = state.sample
                                    val source = state.performanceHudGraphSource(
                                        context.foreground?.packageName,
                                    )
                                    if (sample != null && source != null) {
                                        performanceHudGraphHistory.value = graphAccumulator.record(
                                            source = source,
                                            sampleIdentity = sample.timestampNanos,
                                            framesPerSecond = sample.performanceHudFramesPerSecond(),
                                            cpuLoadPercent = sample.performanceHudCpuLoadPercent(),
                                            gpuBusyPercent = sample.performanceHudGpuBusyPercent(),
                                        )
                                    }
                                    performanceHudTelemetry.value = state
                                }
                            }.onFailure { failure ->
                                if (failure is CancellationException) throw failure
                                Log.w(TAG, "Performance HUD telemetry stopped", failure)
                                performanceHudTelemetry.value = PerformanceHudTelemetryState(
                                    message = failure.message ?: "Telemetry unavailable",
                                )
                            }
                            delay(PERFORMANCE_HUD_RETRY_INTERVAL_MS)
                        }
                    }
                }
            }
        }
        QuickSettingsTileRefresher.requestUpdate(applicationContext)
    }

    private fun commitPerformanceHudTarget(
        expectedPackageName: String,
        expectedTargetFps: Int,
        targetFps: Int,
    ) {
        if (targetFps <= 0) return
        val capturedForeground = performanceHudForeground.value
        val capturedRuntime = AdaptiveTuneRuntime.state.value
        val capturedPackageName = capturedRuntime.packageName.takeIf { capturedRuntime.active }
            ?: capturedForeground?.packageName
            ?: return
        if (capturedPackageName != expectedPackageName) return
        lifecycleScope.launch {
            if (!isPerformanceHudEnabled()) return@launch
            val currentRuntime = AdaptiveTuneRuntime.state.value
            val currentPackageName = currentRuntime.packageName.takeIf { currentRuntime.active }
                ?: performanceHudForeground.value?.packageName
            if (currentPackageName != expectedPackageName || !windowController.isPerformanceHudShowing) {
                return@launch
            }
            val maximum = currentPerformanceHudRefreshRateFps()
                ?: expectedTargetFps
            container.profileStorage.updateAutoTuneTargetIfCurrent(
                packageName = expectedPackageName,
                expectedTargetFps = expectedTargetFps,
                targetFps = targetFps.coerceIn(1, maximum.coerceAtLeast(1)),
            )
        }
    }

    private fun currentPerformanceHudRefreshRateFps(): Int? {
        val display = getSystemService(DisplayManager::class.java)
            ?.getDisplay(overlayDisplayId)
            ?.takeUnless { it.state == Display.STATE_OFF }
            ?: return null
        return nominalDisplayRefreshRateFps(display.mode.refreshRate)
    }

    private fun showCompactProfilePickerOverlay(
        foregroundApp: ForegroundAppInfo? = null,
        initialSettings: AppSettings,
    ): Boolean {
        if (!OverlayPermission.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot show compact profile picker overlay")
            dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
            return false
        }
        val view = buildCompactProfilePickerView(foregroundApp, initialSettings)
        return runCatching {
            windowController.show(
                type = OverlayType.COMPACT_PROFILE_PICKER,
                view = view,
                onBackPressed = {
                    dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                },
            )
            true
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to show compact profile picker overlay", throwable)
            stopIfIdle()
        }.getOrDefault(false)
    }

    private fun buildCompactProfilePickerView(
        foregroundApp: ForegroundAppInfo?,
        initialSettings: AppSettings,
    ): ComposeView {
        val initialExternal = foregroundApp?.takeUnless { it.packageName in foregroundIgnoredPackages }
        compactProfilePickerForeground.value = initialExternal
        return OverlayComposeViewFactory.create(this, this, this, this) {
                val settings by container.settingsStorage.settings.collectAsStateWithLifecycle(
                    initialValue = initialSettings,
                )
                val state by viewModel.state.collectAsStateWithLifecycle()
                val applyingProfileId by viewModel.applyingProfileId.collectAsStateWithLifecycle()
                val currentForegroundApp by compactProfilePickerForeground.collectAsStateWithLifecycle()
                val overlayMode by compactOverlayMode.collectAsStateWithLifecycle()
                ClusterTuneTheme(settings = settings) {
                    CtCompactOverlayFrame(
                        onDismissRequest = { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                    ) {
                        CompactOverlayScreen(
                            state = state,
                            autoTuneEnabled = settings.autoTuneEnabled,
                            applyingProfileId = applyingProfileId,
                            displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                            mode = overlayMode,
                            onModeChange = {
                                compactOverlayModeChangedByUser = true
                                compactOverlayMode.value = it
                            },
                            onApplyProfile = { profile, appProfileEnabled ->
                                if (overlayMode == CompactOverlayMode.PROFILES) {
                                    applyProfileFromOverlay(state, profile, currentForegroundApp, appProfileEnabled)
                                }
                            },
                            onApplyCurrent = { tunerState, profile, customValues, appProfileEnabled ->
                                applyCurrentFromOverlay(tunerState, profile, customValues, appProfileEnabled, currentForegroundApp)
                            },
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            onDismissRequest = { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                            contextPackageName = currentForegroundApp?.packageName,
                            contextLabel = currentForegroundApp?.label,
                            contextIcon = currentForegroundApp?.icon,
                            contextDisplayRefreshRateFps = currentForegroundApp?.currentRefreshRateFps,
                            onAppProfileAssignmentChange = currentForegroundApp?.let { app ->
                                { profile, customValues, customGpuMaxFrequencyHz, autoTuneTargetFps ->
                                    val sessionToken = compactProfilePickerSessionToken
                                    compactAssignmentMutationJob?.cancel()
                                    compactAssignmentMutationJob = lifecycleScope.launch {
                                        if (!ensureCurrentCompactProfilePickerTarget(app, sessionToken)) {
                                            compactAssignmentMutationJob = null
                                            stopIfIdle()
                                            return@launch
                                        }
                                        if (profile == null && customValues == null && customGpuMaxFrequencyHz == null &&
                                            autoTuneTargetFps == null
                                        ) {
                                            viewModel.deleteAppProfileAssignmentAwait(app.packageName)
                                        } else if (profile != null) {
                                            // A named profile is self-contained; do not freeze its
                                            // current values as custom assignment metadata.
                                            viewModel.saveAppProfileAssignmentAwait(
                                                app.packageName,
                                                app.label,
                                                profile.id,
                                            )
                                        } else if (autoTuneTargetFps != null) {
                                            viewModel.saveAppProfileAssignmentAwait(
                                                app.packageName,
                                                app.label,
                                                profileId = null,
                                                autoTuneTargetFps = autoTuneTargetFps,
                                            )
                                        } else {
                                            viewModel.saveAppProfileAssignmentAwait(
                                                app.packageName,
                                                app.label,
                                                profile?.id,
                                                customMaxFrequencies = customValues ?: emptyMap(),
                                                customGpuMaxFrequencyHz = customGpuMaxFrequencyHz,
                                                autoTuneTargetFps = null,
                                            )
                                        }
                                        compactAssignmentMutationJob = null
                                        stopIfIdle()
                                    }
                                }
                            },
                        )
                    }
                }
        }
    }

    private fun showEdgeHandleIfEnabled(preview: EdgeHandleAppearance? = null) {
        lifecycleScope.launch {
            val settings = container.settingsStorage.settings.first()
            if (
                !settings.leftEdgeProfilePickerEnabled ||
                !OverlayPermission.canDrawOverlays(this@OverlayHostService) ||
                !AppProfileAccessibilityAccess.isEnabled(this@OverlayHostService)
            ) {
                keepEdgeHandle = false
                windowController.removeEdgeHandle()
                stopIfIdle()
                return@launch
            }

            keepEdgeHandle = true
            val appearance = preview ?: EdgeHandleAppearance(
                heightDp = settings.edgeHandleHeightDp,
                thicknessDp = settings.edgeHandleThicknessDp,
                verticalPositionPercent = settings.edgeHandleVerticalPositionPercent,
                opacityPercent = settings.edgeHandleOpacityPercent,
            )
            edgeHandleAppearance.value = appearance
            runCatching {
                windowController.showEdgeHandle(
                    view = buildEdgeHandleView(),
                    config = EdgeHandleWindowConfig(
                        heightDp = appearance.heightDp,
                        verticalPositionPercent = appearance.verticalPositionPercent,
                    ),
                )
            }.onFailure { throwable ->
                keepEdgeHandle = false
                Log.e(TAG, "Failed to show profile edge handle", throwable)
                stopIfIdle()
            }
        }
    }

    private fun previewEdgeHandle(intent: Intent) {
        val appearance = intent.edgeHandleAppearanceExtra() ?: return
        edgeHandleAppearance.value = appearance
        if (keepEdgeHandle) {
            windowController.updateEdgeHandleConfig(
                EdgeHandleWindowConfig(
                    heightDp = appearance.heightDp,
                    verticalPositionPercent = appearance.verticalPositionPercent,
                ),
            )
        } else {
            showEdgeHandleIfEnabled(preview = appearance)
        }
    }

    private fun buildEdgeHandleView(): ComposeView {
        return OverlayComposeViewFactory.create(this, this, this, this).apply {
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                updateSystemGestureExclusion(view)
            }
            doOnLayout(::updateSystemGestureExclusion)
            setContent {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val appearance by edgeHandleAppearance.collectAsStateWithLifecycle()
                val swipeThresholdPx = with(LocalDensity.current) { EDGE_SWIPE_THRESHOLD_DP.dp.toPx() }
                var dragDistance by remember { mutableFloatStateOf(0f) }
                ClusterTuneTheme(settings = settings) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(swipeThresholdPx) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragDistance = 0f },
                                    onDragCancel = { dragDistance = 0f },
                                    onDragEnd = {
                                        if (dragDistance >= swipeThresholdPx) {
                                            openProfilePickerForForegroundApp()
                                        }
                                        dragDistance = 0f
                                    },
                                    onHorizontalDrag = { change, amount ->
                                        if (amount > 0f) {
                                            dragDistance += amount
                                            change.consume()
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(
                            modifier = Modifier
                                .width((appearance?.thicknessDp ?: settings.edgeHandleThicknessDp).dp)
                                .fillMaxHeight()
                                .alpha((appearance?.opacityPercent ?: settings.edgeHandleOpacityPercent) / 100f)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.3f)
                                    .fillMaxHeight(0.47f)
                                    .background(
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateSystemGestureExclusion(view: android.view.View) {
        view.systemGestureExclusionRects = listOf(
            Rect(0, 0, view.width, view.height),
        )
    }

    private fun openProfilePickerForForegroundApp() {
        startCompactProfilePickerSession(CompactOverlayMode.PROFILES)
    }

    private fun startCompactProfilePickerSession(mode: CompactOverlayMode) {
        cancelCompactProfilePickerSession()
        val sessionToken = compactProfilePickerSessionToken
        compactOverlayModeChangedByUser = false
        compactOverlayMode.value = mode
        compactProfilePickerSessionJob = lifecycleScope.launch {
            val (initialSettings, initial, assignments) = try {
                coroutineScope {
                    val settings = async { container.settingsStorage.settings.first() }
                    val storedAssignments = async { container.profileStorage.appProfileAssignments.first() }
                    val foreground = async(Dispatchers.Default) {
                        val target = compactProfilePickerTargetSnapshot(
                            snapshot = VisibleAppWindowEvents.snapshots.value,
                            targetDisplayId = overlayDisplayId,
                            excludedPackages = foregroundExcludedPackages,
                        ) ?: return@async null
                        foregroundAppResolver.resolve(
                            snapshot = target,
                            targetDisplayId = overlayDisplayId,
                            excludedPackages = foregroundExcludedPackages,
                        )
                    }
                    Triple(settings.await(), foreground.await(), storedAssignments.await())
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to prepare compact profile picker", error)
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            applyCompactProfilePickerForegroundDetection(
                detected = initial,
                hasFilteredVisibleWindow = false,
                requestedMode = mode,
                assignments = assignments,
                sessionToken = sessionToken,
            )
            if (!showCompactProfilePickerOverlay(initial, initialSettings)) {
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            VisibleAppWindowEvents.snapshots
                .distinctUntilChangedBy { snapshot ->
                    val preferredPackageName = compactProfilePickerForeground.value?.packageName
                    val observation = observeCompactProfilePickerSnapshot(
                        snapshot = snapshot,
                        targetDisplayId = overlayDisplayId,
                        excludedPackages = foregroundExcludedPackages,
                        pickerShowing = windowController.isShowing(OverlayType.COMPACT_PROFILE_PICKER),
                        preferredPackageName = preferredPackageName,
                    )
                    Triple(
                        observation.packageName,
                        observation.isObscured,
                        snapshot.refreshRateFpsByDisplay[overlayDisplayId],
                    )
                }
                .collect { snapshot ->
                    if (!windowController.isShowing(OverlayType.COMPACT_PROFILE_PICKER)) return@collect
                    val preferredPackageName = compactProfilePickerForeground.value?.packageName
                    val observation = observeCompactProfilePickerSnapshot(
                        snapshot = snapshot,
                        targetDisplayId = overlayDisplayId,
                        excludedPackages = foregroundExcludedPackages,
                        pickerShowing = true,
                        preferredPackageName = preferredPackageName,
                    )
                    val detected = if (observation.packageName == null) {
                        null
                    } else {
                        withContext(Dispatchers.Default) {
                            foregroundAppResolver.resolve(
                                snapshot = requireNotNull(observation.targetSnapshot),
                                targetDisplayId = overlayDisplayId,
                                excludedPackages = foregroundExcludedPackages,
                                preferredPackageName = preferredPackageName,
                            )
                        }
                    }
                    applyCompactProfilePickerForegroundDetection(
                        detected = detected,
                        hasFilteredVisibleWindow = observation.isObscured,
                        requestedMode = mode,
                        assignments = assignments,
                        sessionToken = sessionToken,
                    )
                }
        }
    }

    private fun applyCompactProfilePickerForegroundDetection(
        detected: ForegroundAppInfo?,
        hasFilteredVisibleWindow: Boolean,
        requestedMode: CompactOverlayMode,
        assignments: List<AppProfileAssignment>,
        sessionToken: Long,
    ) {
        if (sessionToken != compactProfilePickerSessionToken) return
        val updated = updateCompactProfilePickerForeground(
            current = compactProfilePickerForeground.value,
            detected = detected,
            ignoredPackages = foregroundIgnoredPackages,
            hasFilteredVisibleWindow = hasFilteredVisibleWindow,
        )
        compactProfilePickerForeground.value = updated
        compactOverlayMode.value = correctedCompactOverlayMode(
            requestedMode = requestedMode,
            currentMode = compactOverlayMode.value,
            foregroundPackageName = updated?.packageName,
            assignments = assignments,
            modeChangedByUser = compactOverlayModeChangedByUser,
        )
    }

    private fun cancelCompactProfilePickerSession() {
        compactProfilePickerSessionToken += 1L
        compactProfilePickerSessionJob?.cancel()
        compactProfilePickerSessionJob = null
        compactProfilePickerForeground.value = null
    }

    private fun ensureCurrentCompactProfilePickerTarget(
        captured: ForegroundAppInfo,
        sessionToken: Long,
    ): Boolean {
        if (
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = captured.packageName,
                currentPackageName = compactProfilePickerForeground.value?.packageName,
                capturedSessionToken = sessionToken,
                currentSessionToken = compactProfilePickerSessionToken,
            )
        ) {
            return true
        }
        if (sessionToken == compactProfilePickerSessionToken) {
            SingleToast.show(
                applicationContext,
                "Foreground app changed. Reopen the picker and try again.",
                Toast.LENGTH_SHORT,
            )
            dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
        }
        return false
    }

    private fun hideEdgeHandle() {
        keepEdgeHandle = false
        windowController.removeEdgeHandle()
        stopIfIdle()
    }

    private fun applyCurrentFromOverlay(
        state: TunerState,
        assignmentProfile: PerformanceProfile?,
        customMaxFrequencies: Map<Int, Int>?,
        appProfileEnabled: Boolean,
        foregroundApp: ForegroundAppInfo?,
    ) {
        val sessionToken = compactProfilePickerSessionToken
        lifecycleScope.launch {
            runCompactProfilePickerMutationIfCurrent(
                capturedForeground = foregroundApp,
                ensureCurrent = {
                    foregroundApp == null ||
                        ensureCurrentCompactProfilePickerTarget(foregroundApp, sessionToken)
                },
            ) {
                val applyingToken = assignmentProfile?.let { viewModel.beginApplyingProfile(it.id) }
                try {
                    if (foregroundApp != null && appProfileEnabled) {
                        viewModel.saveAppProfileAssignmentAwait(
                            foregroundApp.packageName,
                            foregroundApp.label,
                            assignmentProfile?.id,
                            customMaxFrequencies ?: emptyMap(),
                            state.currentGpuMaxFrequencyHz.takeIf { assignmentProfile == null },
                        )
                        // The accessibility coordinator is the sole app-profile
                        // writer. Saving the assignment wakes it immediately and
                        // lets it combine this target with apps on other displays.
                        dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                        return@runCompactProfilePickerMutationIfCurrent
                    }
                    val handler = QuickTunerApplyHandler(
                        repository = PerformanceQuickTunerApplyRepository(container.repository),
                        showToast = { message, duration ->
                            SingleToast.show(applicationContext, message, duration)
                        },
                        refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
                    )
                    handler.applyCurrent(state).onSuccess {
                        foregroundApp?.let { app ->
                            if (!ensureCurrentCompactProfilePickerTarget(app, sessionToken)) {
                                return@onSuccess
                            }
                            viewModel.deleteAppProfileAssignmentAwait(app.packageName)
                        }
                        dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                    }
                } finally {
                    applyingToken?.let(viewModel::finishApplyingProfile)
                }
            }
        }
    }

    private fun applyProfileFromOverlay(
        state: TunerState,
        profile: PerformanceProfile,
        foregroundApp: ForegroundAppInfo?,
        appProfileEnabled: Boolean,
    ) {
        val sessionToken = compactProfilePickerSessionToken
        lifecycleScope.launch {
            runCompactProfilePickerMutationIfCurrent(
                capturedForeground = foregroundApp,
                ensureCurrent = {
                    foregroundApp == null ||
                        ensureCurrentCompactProfilePickerTarget(foregroundApp, sessionToken)
                },
            ) {
                val applyingToken = viewModel.beginApplyingProfile(profile.id)
                try {
                    if (foregroundApp != null && appProfileEnabled) {
                        viewModel.saveAppProfileAssignmentAwait(
                            foregroundApp.packageName,
                            foregroundApp.label,
                            profile.id,
                        )
                        // Applying is delegated to the event coordinator so
                        // multi-display assignments produce one combined write.
                        dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                        return@runCompactProfilePickerMutationIfCurrent
                    }
                    val handler = QuickTunerApplyHandler(
                        repository = PerformanceQuickTunerApplyRepository(container.repository),
                        showToast = { message, duration ->
                            SingleToast.show(applicationContext, message, duration)
                        },
                        refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
                    )
                    handler.applyProfile(state, profile).onSuccess {
                        foregroundApp?.let { app ->
                            if (!ensureCurrentCompactProfilePickerTarget(app, sessionToken)) {
                                return@onSuccess
                            }
                            if (appProfileEnabled) {
                                viewModel.saveAppProfileAssignmentAwait(app.packageName, app.label, profile.id)
                            } else {
                                viewModel.deleteAppProfileAssignmentAwait(app.packageName)
                            }
                        }
                        dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                    }
                } finally {
                    viewModel.finishApplyingProfile(applyingToken)
                }
            }
        }
    }

    private fun dismissOverlay(type: OverlayType? = null) {
        if (type == null || type == OverlayType.COMPACT_PROFILE_PICKER) {
            cancelCompactProfilePickerSession()
        }
        viewModel.discardEdits()
        windowController.dismiss(type)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (!keepEdgeHandle && !windowController.hasActiveOverlay && compactAssignmentMutationJob?.isActive != true) {
            stopSelf()
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        dismissOverlay()
    }

    private fun registerScreenReceiver() {
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun Intent.overlayTypeExtra(): OverlayType? {
        val rawType = getStringExtra(EXTRA_OVERLAY_TYPE) ?: return null
        return runCatching { OverlayType.valueOf(rawType) }.getOrNull()
    }

    private fun Intent.edgeHandleAppearanceExtra(): EdgeHandleAppearance? {
        if (
            !hasExtra(EXTRA_EDGE_HANDLE_HEIGHT_DP) ||
            !hasExtra(EXTRA_EDGE_HANDLE_THICKNESS_DP) ||
            !hasExtra(EXTRA_EDGE_HANDLE_VERTICAL_POSITION_PERCENT) ||
            !hasExtra(EXTRA_EDGE_HANDLE_OPACITY_PERCENT)
        ) {
            return null
        }
        return EdgeHandleAppearance(
            heightDp = getIntExtra(EXTRA_EDGE_HANDLE_HEIGHT_DP, 0),
            thicknessDp = getIntExtra(EXTRA_EDGE_HANDLE_THICKNESS_DP, 0),
            verticalPositionPercent = getIntExtra(EXTRA_EDGE_HANDLE_VERTICAL_POSITION_PERCENT, 0),
            opacityPercent = getIntExtra(EXTRA_EDGE_HANDLE_OPACITY_PERCENT, 0),
        )
    }

    companion object {
        private const val TAG = "OverlayHostService"
        private const val ACTION_SHOW_COMPACT_TUNER = "com.aure.clustertune.overlay.SHOW_COMPACT_TUNER"
        private const val ACTION_SHOW_PROFILE_PICKER = "com.aure.clustertune.overlay.SHOW_PROFILE_PICKER"
        private const val ACTION_SHOW_EDGE_HANDLE = "com.aure.clustertune.overlay.SHOW_EDGE_HANDLE"
        private const val ACTION_TOGGLE_PERFORMANCE_HUD = "com.aure.clustertune.overlay.TOGGLE_PERFORMANCE_HUD"
        private const val ACTION_PREVIEW_EDGE_HANDLE = "com.aure.clustertune.overlay.PREVIEW_EDGE_HANDLE"
        private const val ACTION_HIDE_EDGE_HANDLE = "com.aure.clustertune.overlay.HIDE_EDGE_HANDLE"
        private const val ACTION_DISMISS = "com.aure.clustertune.overlay.DISMISS"
        private const val EXTRA_OVERLAY_TYPE = "overlay_type"
        private const val EXTRA_EDGE_HANDLE_HEIGHT_DP = "edge_handle_height_dp"
        private const val EXTRA_EDGE_HANDLE_THICKNESS_DP = "edge_handle_thickness_dp"
        private const val EXTRA_EDGE_HANDLE_VERTICAL_POSITION_PERCENT = "edge_handle_vertical_position_percent"
        private const val EXTRA_EDGE_HANDLE_OPACITY_PERCENT = "edge_handle_opacity_percent"
        private const val EDGE_SWIPE_THRESHOLD_DP = 48
        private const val PERFORMANCE_HUD_SAMPLE_INTERVAL_MS = 1_000L
        private const val PERFORMANCE_HUD_RETRY_INTERVAL_MS = 1_500L
        fun showCompactTuner(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_SHOW_COMPACT_TUNER
                },
            )
        }

        fun showProfilePicker(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_SHOW_PROFILE_PICKER
                },
            )
        }

        fun togglePerformanceHud(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_TOGGLE_PERFORMANCE_HUD
                },
            )
        }

        fun showEdgeHandle(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_SHOW_EDGE_HANDLE
                },
            )
        }

        fun previewEdgeHandle(
            context: Context,
            heightDp: Int,
            thicknessDp: Int,
            verticalPositionPercent: Int,
            opacityPercent: Int,
        ) {
            context.startService(
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_PREVIEW_EDGE_HANDLE
                    putExtra(EXTRA_EDGE_HANDLE_HEIGHT_DP, heightDp)
                    putExtra(EXTRA_EDGE_HANDLE_THICKNESS_DP, thicknessDp)
                    putExtra(EXTRA_EDGE_HANDLE_VERTICAL_POSITION_PERCENT, verticalPositionPercent)
                    putExtra(EXTRA_EDGE_HANDLE_OPACITY_PERCENT, opacityPercent)
                },
            )
        }

        fun hideEdgeHandle(context: Context) {
            context.startService(
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_HIDE_EDGE_HANDLE
                },
            )
        }

        fun dismiss(context: Context, overlayType: OverlayType? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayHostService::class.java).apply {
                    action = ACTION_DISMISS
                    overlayType?.let { putExtra(EXTRA_OVERLAY_TYPE, it.name) }
                },
            )
        }
    }
}
