package com.aure.clustertune.tile

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.aure.clustertune.AppContainer
import com.aure.clustertune.R
import com.aure.clustertune.TileControlActivity
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.overlay.OverlayPermission
import com.aure.clustertune.overlay.OverlayHostService
import com.aure.clustertune.ui.SingleToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

internal fun resolveTileProfileId(state: TunerState): String? {
    fun isKnownProfile(id: String): Boolean {
        return id == ProfileStateResolver.MANUAL_PROFILE_ID ||
            id == ProfileStateResolver.STOCK_PROFILE_ID ||
            state.displayProfiles.any { profile -> profile.id == id }
    }

    return state.activeDisplayProfileId
        ?.takeIf(::isKnownProfile)
        ?: state.lastAppliedDisplayProfileId?.takeIf(::isKnownProfile)
}

internal enum class TileTapAction {
    SHOW_DIALOG,
    SHOW_PROFILE_PICKER,
    OPEN_APP,
    CYCLE_PROFILES,
    REQUEST_OVERLAY_PERMISSION,
}
internal fun resolveTileTapAction(
    behavior: TileInteractionBehavior,
    canDrawOverlays: Boolean,
): TileTapAction = when (behavior) {
    TileInteractionBehavior.SHOW_DIALOG ->
        if (canDrawOverlays) TileTapAction.SHOW_DIALOG else TileTapAction.REQUEST_OVERLAY_PERMISSION
    TileInteractionBehavior.SHOW_PROFILE_PICKER ->
        if (canDrawOverlays) TileTapAction.SHOW_PROFILE_PICKER else TileTapAction.REQUEST_OVERLAY_PERMISSION
    TileInteractionBehavior.OPEN_APP -> TileTapAction.OPEN_APP
    TileInteractionBehavior.CYCLE_PROFILES -> TileTapAction.CYCLE_PROFILES
}

class PerformanceTileService : TileService() {

    companion object {
        private const val TAG = "PerformanceTile"
        private var activeService = WeakReference<PerformanceTileService>(null)

        fun refreshActiveTile(): Boolean {
            val service = activeService.get() ?: return false
            service.serviceScope.launch(Dispatchers.Main.immediate) { service.refreshTileStateAsync() }
            return true
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { AppContainer(applicationContext) }
    private var tileRefreshJob: kotlinx.coroutines.Job? = null
    private var tileRefreshRunning = false
    private var tileRefreshPending = false
    private var cachedTileTapBehavior: TileInteractionBehavior? = null
    private var cacheLoadJob: kotlinx.coroutines.Job? = null
    private var cycleJob: kotlinx.coroutines.Job? = null
    private var collapseBridge: Dialog? = null

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
        serviceScope.launch {
            container.settingsStorage.settings.collect { settings ->
                cachedTileTapBehavior = settings.tileTapBehavior
            }
        }
    }

    override fun onDestroy() {
        tileRefreshJob?.cancel()
        cacheLoadJob?.cancel()
        cycleJob?.cancel()
        collapseBridge?.dismiss()
        collapseBridge = null
        serviceScope.cancel()
        if (activeService.get() === this) {
            activeService.clear()
        }
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        persistTileAddedState(isAdded = true)
        refreshTileStateAsync()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        persistTileAddedState(isAdded = false)
    }

    override fun onStartListening() {
        super.onStartListening()
        persistTileAddedState(isAdded = true)
        refreshTileStateAsync()
    }

    private fun refreshTileStateAsync() {
        if (tileRefreshRunning) {
            tileRefreshPending = true
            return
        }
        tileRefreshRunning = true
        tileRefreshJob = serviceScope.launch {
            runCatching {
                val (state, settings) = withContext(Dispatchers.IO) {
                    val settings = container.settingsStorage.settings.first()
                    container.privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
                    container.repository.observeState().first() to settings
                }
                cachedTileTapBehavior = settings.tileTapBehavior
                val presentation = buildTilePresentation(state, settings)
                qsTile?.apply {
                    label = presentation.label
                    subtitle = presentation.subtitle
                    this.state = buildTileVisualState(state)
                    updateTile()
                }
            }.onFailure { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) return@onFailure
                Log.e(TAG, "Failed to refresh tile state", throwable)
                qsTile?.apply {
                    label = getString(R.string.tile_title)
                    subtitle = getString(R.string.tile_state_unavailable)
                    state = Tile.STATE_INACTIVE
                    updateTile()
                }
            }.also {
                tileRefreshRunning = false
                if (tileRefreshPending && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    tileRefreshPending = false
                    refreshTileStateAsync()
                }
            }
        }
    }

    private data class TilePresentation(
        val label: String,
        val subtitle: String,
    )

    private fun buildTilePresentation(state: TunerState, settings: AppSettings): TilePresentation {
        if (!state.isPServerAvailable) {
            return TilePresentation(
                label = getString(R.string.tile_title),
                subtitle = getString(R.string.tile_state_unavailable),
            )
        }
        val currentName = effectiveTileProfileName(state) ?: getString(R.string.tile_state_manual)
        if (settings.tileTapBehavior != TileInteractionBehavior.CYCLE_PROFILES) {
            return TilePresentation(
                label = getString(R.string.tile_title),
                subtitle = currentName,
            )
        }
        return TilePresentation(
            label = currentName,
            subtitle = getString(R.string.tile_title),
        )
    }

    private fun buildTileVisualState(state: TunerState): Int {
        if (!state.isPServerAvailable) return Tile.STATE_INACTIVE
        val activeProfileId = effectiveTileProfileId(state)
        val activeName = effectiveTileProfileName(state)
        val stockIsActive = activeProfileId == ProfileStateResolver.STOCK_PROFILE_ID || activeName == "Stock"
        return if (stockIsActive) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
    }

    private fun effectiveTileProfileId(state: TunerState): String? {
        return resolveTileProfileId(state)
    }

    private fun effectiveTileProfileName(state: TunerState): String? {
        val effectiveId = effectiveTileProfileId(state)
        if (effectiveId == ProfileStateResolver.MANUAL_PROFILE_ID) {
            return getString(R.string.tile_state_manual)
        }
        return state.displayProfiles.firstOrNull { it.id == effectiveId }?.name
            ?: state.activeDisplayProfileName
    }

    private fun persistTileAddedState(isAdded: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            container.settingsStorage.persistQuickSettingsTileAdded(isAdded)
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun(::handleTap)
        } else {
            handleTap()
        }
    }

    private fun handleTap() {
        val behavior = cachedTileTapBehavior
        if (behavior == null) {
            if (cacheLoadJob?.isActive != true) {
                cacheLoadJob = serviceScope.launch {
                    try {
                        val settings = withContext(Dispatchers.IO) { container.settingsStorage.settings.first() }
                        cachedTileTapBehavior = settings.tileTapBehavior
                        dispatchTap(settings.tileTapBehavior)
                    } catch (error: Throwable) {
                        if (error !is kotlinx.coroutines.CancellationException) {
                            showToast(error.message ?: "Unable to read tile settings")
                        }
                    } finally {
                        cacheLoadJob = null
                    }
                }
            }
            return
        }
        dispatchTap(behavior)
    }

    private fun dispatchTap(behavior: TileInteractionBehavior) {
        runCatching {
            when (resolveTileTapAction(behavior, OverlayPermission.canDrawOverlays(applicationContext))) {
                TileTapAction.SHOW_DIALOG -> showOverlayWithBridge(
                    launch = { OverlayHostService.showCompactTuner(it) },
                    fallback = ::showOverlayAndCollapse,
                )
                TileTapAction.SHOW_PROFILE_PICKER -> showOverlayWithBridge(
                    launch = { OverlayHostService.showProfilePicker(it) },
                    fallback = ::showProfilePickerOverlayAndCollapse,
                )
                TileTapAction.REQUEST_OVERLAY_PERMISSION -> requestOverlayAccessAndCollapse()
                TileTapAction.OPEN_APP -> launchAppAndCollapse()
                TileTapAction.CYCLE_PROFILES -> {
                    if (cycleJob?.isActive == true) return@runCatching
                    cycleJob = serviceScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { runCatching { container.repository.cycleTileProfile() }.getOrThrow() }
                            result.onSuccess { profile -> showToast(profile.name) }
                                .onFailure { error -> showToast(error.message ?: "Failed to cycle profile") }
                            refreshTileStateAsync()
                        } catch (error: Throwable) {
                            if (error !is kotlinx.coroutines.CancellationException) {
                                showToast(error.message ?: "Failed to cycle profile")
                            }
                        } finally {
                            cycleJob = null
                        }
                    }
                }
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to handle tile tap", throwable)
            showToast(throwable.message ?: "Failed to handle tile tap")
        }
    }

    private fun showToast(message: String) {
        SingleToast.show(applicationContext, message, Toast.LENGTH_SHORT)
    }

    private fun requestOverlayAccessAndCollapse() {
        showToast("Allow overlay access to use Quick Settings controls")
        launchIntentAndCollapse(
            OverlayPermission.createSettingsIntent(applicationContext),
        )
    }

    @Suppress("DEPRECATION")
    private fun showOverlayAndCollapse() {
        launchIntentAndCollapse(
            TileControlActivity.createCompactTunerOverlayIntent(applicationContext),
        )
    }

    @Suppress("DEPRECATION")
    private fun showProfilePickerOverlayAndCollapse() {
        launchIntentAndCollapse(
            TileControlActivity.createProfilePickerOverlayIntent(applicationContext),
        )
    }

    private fun showOverlayWithBridge(
        launch: (android.content.Context) -> Unit,
        fallback: () -> Unit,
    ) {
        if (collapseBridge != null) return
        val bridge = Dialog(this)
        bridge.setCanceledOnTouchOutside(false)
        bridge.setContentView(View(this))
        bridge.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            attributes = attributes.apply {
                width = 1
                height = 1
                dimAmount = 0f
            }
        }
        try {
            showDialog(bridge)
            collapseBridge = bridge
            launch(applicationContext)
            bridge.window?.decorView?.postDelayed({
                if (collapseBridge === bridge) {
                    collapseBridge = null
                    bridge.dismiss()
                }
            }, 100L)
        } catch (error: Throwable) {
            Log.w(TAG, "Dialog collapse bridge unavailable; using activity trampoline", error)
            collapseBridge = null
            runCatching { bridge.dismiss() }
            fallback()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchAppAndCollapse() {
        val intent = Intent(applicationContext, com.aure.clustertune.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launchIntentAndCollapse(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchIntentAndCollapse(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
