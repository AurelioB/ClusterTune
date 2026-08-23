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
import android.view.Display
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
import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.ForegroundAppResolver
import com.aure.clustertune.apps.VENDOR_GAME_ASSISTANT_PACKAGES
import com.aure.clustertune.apps.VisibleAppWindowEvents
import com.aure.clustertune.permissions.AppProfileAccessibilityAccess
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.quicktuner.PerformanceQuickTunerApplyRepository
import com.aure.clustertune.quicktuner.QuickTunerApplyHandler
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.CompactOverlayMode
import com.aure.clustertune.ui.CompactOverlayScreen
import com.aure.clustertune.ui.SingleToast
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import com.aure.clustertune.ui.designsystem.component.CtCompactOverlayFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class EdgeHandleAppearance(
    val heightDp: Int,
    val thicknessDp: Int,
    val verticalPositionPercent: Int,
    val opacityPercent: Int,
)

internal const val SYSTEM_UI_PACKAGE = "com.android.systemui"

internal fun updateCompactProfilePickerForeground(
    current: ForegroundAppInfo?,
    detected: ForegroundAppInfo?,
    ignoredPackages: Set<String> = emptySet(),
): ForegroundAppInfo? {
    if (detected != null && detected.packageName in ignoredPackages) {
        return current
    }
    return detected
}

class OverlayHostService : LifecycleService(), ViewModelStoreOwner, SavedStateRegistryOwner {
    private val foregroundIgnoredPackages by lazy { setOf(packageName, SYSTEM_UI_PACKAGE) }
    private val foregroundExcludedPackages = VENDOR_GAME_ASSISTANT_PACKAGES

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
    private var compactAssignmentMutationJob: Job? = null
    private val compactOverlayMode = MutableStateFlow(CompactOverlayMode.PROFILES)
    private val compactProfilePickerForeground = MutableStateFlow<ForegroundAppInfo?>(null)
    private val edgeHandleAppearance = MutableStateFlow<EdgeHandleAppearance?>(null)

    // OverlayWindowController uses applicationContext's WindowManager, which
    // renders on the default display. Service contexts may be non-visual and
    // throw from getDisplay(), so keep this explicit.
    private val overlayDisplayId = Display.DEFAULT_DISPLAY

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
        startCompactProfilePickerSession(CompactOverlayMode.TUNER)
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
                            applyingProfileId = applyingProfileId,
                            displayFrequenciesAsPercent = settings.displayFrequenciesAsPercent,
                            mode = overlayMode,
                            onModeChange = { compactOverlayMode.value = it },
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
                            onAppProfileAssignmentChange = currentForegroundApp?.let { app ->
                                { profile, customValues, customGpuMaxFrequencyHz ->
                                    compactAssignmentMutationJob?.cancel()
                                    compactAssignmentMutationJob = lifecycleScope.launch {
                                        if (profile == null && customValues == null && customGpuMaxFrequencyHz == null) {
                                            viewModel.deleteAppProfileAssignmentAwait(app.packageName)
                                        } else if (profile != null) {
                                            // A named profile is self-contained; do not freeze its
                                            // current values as custom assignment metadata.
                                            viewModel.saveAppProfileAssignmentAwait(
                                                app.packageName,
                                                app.label,
                                                profile.id,
                                            )
                                        } else {
                                            viewModel.saveAppProfileAssignmentAwait(
                                                app.packageName,
                                                app.label,
                                                profile?.id,
                                                customMaxFrequencies = customValues ?: emptyMap(),
                                                customGpuMaxFrequencyHz = customGpuMaxFrequencyHz,
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
        compactOverlayMode.value = mode
        compactProfilePickerSessionJob = lifecycleScope.launch {
            val (initialSettings, initial) = try {
                coroutineScope {
                    val settings = async { container.settingsStorage.settings.first() }
                    val foreground = async(Dispatchers.Default) {
                        foregroundAppResolver.resolve(
                            targetDisplayId = overlayDisplayId,
                            excludedPackages = foregroundExcludedPackages,
                        )
                    }
                    settings.await() to foreground.await()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Failed to prepare compact profile picker", error)
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            if (!showCompactProfilePickerOverlay(initial, initialSettings)) {
                dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                return@launch
            }
            VisibleAppWindowEvents.snapshots
                .distinctUntilChangedBy { snapshot ->
                    foregroundAppResolver.selectPackageName(
                        snapshot,
                        overlayDisplayId,
                        foregroundExcludedPackages,
                    )
                }
                .collect { snapshot ->
                    if (!windowController.isShowing(OverlayType.COMPACT_PROFILE_PICKER)) return@collect
                    val detected = withContext(Dispatchers.Default) {
                        foregroundAppResolver.resolve(
                            snapshot,
                            overlayDisplayId,
                            foregroundExcludedPackages,
                        )
                    }
                    val updated = updateCompactProfilePickerForeground(
                        compactProfilePickerForeground.value,
                        detected,
                        foregroundIgnoredPackages,
                    )
                    compactProfilePickerForeground.value = updated
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

    private fun applyCurrentFromOverlay(
        state: TunerState,
        assignmentProfile: PerformanceProfile?,
        customMaxFrequencies: Map<Int, Int>?,
        appProfileEnabled: Boolean,
        foregroundApp: ForegroundAppInfo?,
    ) {
        lifecycleScope.launch {
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
                    return@launch
                }
                val handler = QuickTunerApplyHandler(
                    repository = PerformanceQuickTunerApplyRepository(container.repository),
                    showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                    refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
                )
                handler.applyCurrent(state).onSuccess {
                    foregroundApp?.let { app ->
                        viewModel.deleteAppProfileAssignmentAwait(app.packageName)
                    }
                    dismissOverlay(OverlayType.COMPACT_PROFILE_PICKER)
                }
            } finally {
                applyingToken?.let(viewModel::finishApplyingProfile)
            }
        }
    }

    private fun applyProfileFromOverlay(
        state: TunerState,
        profile: PerformanceProfile,
        foregroundApp: ForegroundAppInfo?,
        appProfileEnabled: Boolean,
    ) {
        lifecycleScope.launch {
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
                    return@launch
                }
                val handler = QuickTunerApplyHandler(
                    repository = PerformanceQuickTunerApplyRepository(container.repository),
                    showToast = { message, duration -> SingleToast.show(applicationContext, message, duration) },
                    refreshTile = { QuickSettingsTileRefresher.requestUpdate(applicationContext) },
                )
                handler.applyProfile(state, profile).onSuccess {
                    foregroundApp?.let { app ->
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
