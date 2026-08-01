package com.aure.clustertune.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import androidx.compose.material3.surfaceColorAtElevation
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
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
import com.aure.clustertune.apps.AppProfileMonitorService
import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.ForegroundAppResolver
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.quicktuner.PerformanceQuickTunerApplyRepository
import com.aure.clustertune.quicktuner.QuickTunerApplyHandler
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.CompactProfilePickerScreen
import com.aure.clustertune.ui.CompactTunerScreen
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import com.aure.clustertune.ui.designsystem.component.CtOverlayFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CompactProfilePickerForegroundState(
    val trackedPackageName: String? = null,
    val foregroundApp: ForegroundAppInfo? = null,
    val consecutiveNullDetections: Int = 0,
)

private data class EdgeHandleAppearance(
    val heightDp: Int,
    val thicknessDp: Int,
    val verticalPositionPercent: Int,
    val opacityPercent: Int,
)

internal data class CompactProfilePickerForegroundUpdate(
    val state: CompactProfilePickerForegroundState,
    val dismissRequested: Boolean,
)

internal fun updateCompactProfilePickerForeground(
    state: CompactProfilePickerForegroundState,
    detected: ForegroundAppInfo?,
): CompactProfilePickerForegroundUpdate {
    if (detected == null) {
        if (state.trackedPackageName == null) {
            return CompactProfilePickerForegroundUpdate(state, dismissRequested = false)
        }
        val nullCount = state.consecutiveNullDetections + 1
        return CompactProfilePickerForegroundUpdate(
            state = state.copy(
                foregroundApp = if (nullCount >= 2) null else state.foregroundApp,
                consecutiveNullDetections = nullCount,
            ),
            dismissRequested = nullCount >= 2,
        )
    }
    val packageChanged = state.trackedPackageName != null &&
        state.trackedPackageName != detected.packageName
    val samePackage = state.trackedPackageName == detected.packageName
    return CompactProfilePickerForegroundUpdate(
        state = state.copy(
            trackedPackageName = state.trackedPackageName ?: detected.packageName,
            foregroundApp = if (samePackage) state.foregroundApp else detected,
            consecutiveNullDetections = 0,
        ),
        dismissRequested = packageChanged,
    )
}

class OverlayHostService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {

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
    private var compactProfilePickerForegroundState = CompactProfilePickerForegroundState()
    private val compactProfilePickerForeground = MutableStateFlow<ForegroundAppInfo?>(null)
    private val edgeHandleAppearance = MutableStateFlow<EdgeHandleAppearance?>(null)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                dismissOverlay()
            }
        }
    }

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHOW_COMPACT_TUNER -> showCompactTunerOverlay()
            ACTION_SHOW_PROFILE_PICKER -> openProfilePickerForForegroundApp()
            ACTION_SHOW_EDGE_HANDLE -> showEdgeHandleIfEnabled()
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
        windowController.refreshEdgeHandleLayout()
    }

    private fun showCompactTunerOverlay() {
        cancelCompactProfilePickerSession()
        if (!OverlayPermission.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot show compact tuner overlay")
            dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
            return
        }
        val view = buildCompactTunerView()
        runCatching {
            windowController.show(
                type = OverlayType.COMPACT_TUNER_MODAL,
                view = view,
                onBackPressed = {
                    dismissOverlay(OverlayType.COMPACT_TUNER_MODAL)
                },
            )
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to show compact tuner overlay", throwable)
            stopIfIdle()
        }
    }

    private fun showCompactProfilePickerOverlay(foregroundApp: ForegroundAppInfo? = null): Boolean {
        if (!OverlayPermission.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing; cannot show compact profile picker overlay")
            dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
            return false
        }
        val view = buildCompactProfilePickerView(foregroundApp)
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

    private fun buildCompactTunerView(): ComposeView {
        return OverlayComposeViewFactory.create(this, this, this, this) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                ClusterTuneTheme(settings = settings) {
                    CtOverlayFrame(
                        onDismissRequest = { dismissOverlay(OverlayType.COMPACT_TUNER_MODAL) },
                        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f),
                        maxWidth = 900.dp,
                        widthFraction = 0.92f,
                        heightFraction = 0.92f,
                        panelShape = RoundedCornerShape(20.dp),
                        panelColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                        panelTonalElevation = 0.dp,
                        panelModifier = Modifier.padding(horizontal = 12.dp, vertical = 24.dp),
                    ) {
                        CompactTunerScreen(
                            state = state,
                            displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                            onPolicyValueChange = viewModel::setPolicyValue,
                            onApplyProfile = viewModel::applyProfile,
                            onClearSelection = viewModel::clearSelection,
                            onApplyCurrent = { tunerState -> applyCurrentFromOverlay(tunerState) },
                            onDismissRequest = { dismissOverlay(OverlayType.COMPACT_TUNER_MODAL) },
                            onRefreshLiveValues = viewModel::refreshLiveState,
                            onOpenFullApp = ::openFullApp,
                            showCompactScrim = false,
                        )
                    }
                }
        }
    }

    private fun buildCompactProfilePickerView(foregroundApp: ForegroundAppInfo?): ComposeView {
        compactProfilePickerForegroundState = CompactProfilePickerForegroundState(
            trackedPackageName = foregroundApp?.packageName,
            foregroundApp = foregroundApp,
        )
        compactProfilePickerForeground.value = foregroundApp
        return OverlayComposeViewFactory.create(this, this, this, this) {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val currentForegroundApp by compactProfilePickerForeground.collectAsStateWithLifecycle()
                ClusterTuneTheme(settings = settings) {
                    CtOverlayFrame(
                        onDismissRequest = { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.46f),
                        maxWidth = 380.dp,
                        widthFraction = 1f,
                        heightFraction = null,
                        panelShape = RoundedCornerShape(20.dp),
                        panelColor = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                        panelTonalElevation = 0.dp,
                        panelModifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        CompactProfilePickerScreen(
                            state = state,
                            onApplyProfile = { profile -> applyProfileFromOverlay(state, profile) },
                            onDismissRequest = { dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER) },
                            showCompactScrim = false,
                            contextPackageName = currentForegroundApp?.packageName,
                            contextLabel = currentForegroundApp?.label,
                            contextIcon = currentForegroundApp?.icon,
                            onAppProfileAssignmentChange = currentForegroundApp?.let { app ->
                                { profile ->
                                    if (profile == null) {
                                        viewModel.deleteAppProfileAssignment(app.packageName)
                                    } else {
                                        viewModel.saveAppProfileAssignment(
                                            app.packageName,
                                            app.label,
                                            profile.id,
                                        )
                                        AppProfileMonitorService.start(this@OverlayHostService)
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
                !AppProfileMonitorService.hasUsageStatsPermission(this@OverlayHostService)
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
        startCompactProfilePickerSession()
    }

    private fun startCompactProfilePickerSession() {
        cancelCompactProfilePickerSession()
        compactProfilePickerSessionJob = lifecycleScope.launch {
            val initial = try {
                withContext(Dispatchers.IO) { foregroundAppResolver.resolve() }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to resolve foreground app for profile picker", error)
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            if (!showCompactProfilePickerOverlay(initial)) {
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            while (isActive && windowController.isShowing(OverlayType.COMPACT_PROFILE_PICKER)) {
                delay(COMPACT_PROFILE_PICKER_POLL_INTERVAL_MS)
                val detected = try {
                    withContext(Dispatchers.IO) { foregroundAppResolver.resolve() }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Failed to monitor foreground app for profile picker", error)
                    dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                    return@launch
                }
                val update = updateCompactProfilePickerForeground(
                    compactProfilePickerForegroundState,
                    detected,
                )
                compactProfilePickerForegroundState = update.state
                // Publish context before requesting dismissal so a delayed removal cannot show stale data.
                compactProfilePickerForeground.value = update.state.foregroundApp
                if (update.dismissRequested) {
                    dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                    break
                }
            }
        }
    }

    private fun cancelCompactProfilePickerSession() {
        compactProfilePickerSessionJob?.cancel()
        compactProfilePickerSessionJob = null
    }

    private fun hideEdgeHandle() {
        keepEdgeHandle = false
        windowController.removeEdgeHandle()
        stopIfIdle()
    }

    private fun applyCurrentFromOverlay(state: TunerState) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyCurrent(state).onSuccess {
                dismissOverlay(OverlayType.COMPACT_TUNER_MODAL)
            }
        }
    }

    private fun applyProfileFromOverlay(state: TunerState, profile: PerformanceProfile) {
        lifecycleScope.launch {
            val handler = QuickTunerApplyHandler(
                repository = PerformanceQuickTunerApplyRepository(container.repository),
                showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
            )
            handler.applyProfile(state, profile).onSuccess {
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
            }
        }
    }

    private fun dismissOverlay(type: OverlayType? = null) {
        if (type == null || type == OverlayType.COMPACT_PROFILE_PICKER) {
            cancelCompactProfilePickerSession()
        }
        windowController.dismiss(type)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (!keepEdgeHandle && !windowController.hasActiveOverlay) {
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ClusterTune overlays",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            description = "Hosts ClusterTune controls over other apps."
        }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_underclock)
            .setContentTitle("ClusterTune controls")
            .setContentText("Profile controls are available over other apps.")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

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
        private const val CHANNEL_ID = "clustertune_overlays"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_SHOW_COMPACT_TUNER = "com.aure.clustertune.overlay.SHOW_COMPACT_TUNER"
        private const val ACTION_SHOW_PROFILE_PICKER = "com.aure.clustertune.overlay.SHOW_PROFILE_PICKER"
        private const val ACTION_SHOW_EDGE_HANDLE = "com.aure.clustertune.overlay.SHOW_EDGE_HANDLE"
        private const val ACTION_PREVIEW_EDGE_HANDLE = "com.aure.clustertune.overlay.PREVIEW_EDGE_HANDLE"
        private const val ACTION_HIDE_EDGE_HANDLE = "com.aure.clustertune.overlay.HIDE_EDGE_HANDLE"
        private const val ACTION_DISMISS = "com.aure.clustertune.overlay.DISMISS"
        private const val EXTRA_OVERLAY_TYPE = "overlay_type"
        private const val EXTRA_EDGE_HANDLE_HEIGHT_DP = "edge_handle_height_dp"
        private const val EXTRA_EDGE_HANDLE_THICKNESS_DP = "edge_handle_thickness_dp"
        private const val EXTRA_EDGE_HANDLE_VERTICAL_POSITION_PERCENT = "edge_handle_vertical_position_percent"
        private const val EXTRA_EDGE_HANDLE_OPACITY_PERCENT = "edge_handle_opacity_percent"
        private const val EDGE_SWIPE_THRESHOLD_DP = 48
        private const val COMPACT_PROFILE_PICKER_POLL_INTERVAL_MS = 400L

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
