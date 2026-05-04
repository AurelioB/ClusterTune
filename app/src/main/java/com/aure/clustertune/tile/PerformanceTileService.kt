package com.aure.clustertune.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.aure.clustertune.AppContainer
import com.aure.clustertune.R
import com.aure.clustertune.TileControlActivity
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference

class PerformanceTileService : TileService() {

    companion object {
        private const val TAG = "PerformanceTile"
        private var activeService = WeakReference<PerformanceTileService>(null)

        fun refreshActiveTile(): Boolean {
            val service = activeService.get() ?: return false
            service.refreshTileState()
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = WeakReference(this)
    }

    override fun onDestroy() {
        if (activeService.get() === this) {
            activeService.clear()
        }
        super.onDestroy()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        persistTileAddedState(isAdded = true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        persistTileAddedState(isAdded = false)
    }

    override fun onStartListening() {
        super.onStartListening()
        persistTileAddedState(isAdded = true)
        refreshTileState()
    }

    private fun refreshTileState() {
        runCatching {
            val container = AppContainer(applicationContext)
            val state = runBlocking { container.repository.observeState().first() }
            val settings = runBlocking { container.settingsStorage.settings.first() }
            val presentation = buildTilePresentation(state, settings)
            qsTile?.apply {
                label = presentation.label
                subtitle = presentation.subtitle
                this.state = buildTileVisualState(state)
                updateTile()
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to refresh tile state", throwable)
            qsTile?.apply {
                label = getString(R.string.tile_title)
                subtitle = getString(R.string.tile_state_unavailable)
                state = Tile.STATE_INACTIVE
                updateTile()
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
        return state.lastAppliedDisplayProfileId
            ?.takeIf { id ->
                id == ProfileStateResolver.MANUAL_PROFILE_ID ||
                    id == ProfileStateResolver.STOCK_PROFILE_ID ||
                    state.displayProfiles.any { profile -> profile.id == id }
            }
            ?: state.activeDisplayProfileId
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
            AppContainer(applicationContext).settingsStorage.persistQuickSettingsTileAdded(isAdded)
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
        runCatching {
            val container = AppContainer(applicationContext)
            val settings = runBlocking { container.settingsStorage.settings.first() }
            when (settings.tileTapBehavior) {
                TileInteractionBehavior.SHOW_DIALOG -> {
                    launchDialogAndCollapse()
                }
                TileInteractionBehavior.OPEN_APP -> {
                    launchAppAndCollapse()
                }
                TileInteractionBehavior.CYCLE_PROFILES -> {
                    runBlocking {
                        container.repository.cycleTileProfile()
                            .onSuccess { profile ->
                                updateTileForAppliedProfile(container, profile)
                                showToast("Applied ${profile.name}")
                            }
                            .onFailure { throwable ->
                                showToast(throwable.message ?: "Failed to cycle profile")
                            }
                    }
                }
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to handle tile tap", throwable)
            showToast(throwable.message ?: "Failed to handle tile tap")
        }
    }

    private fun updateTileForAppliedProfile(container: AppContainer, profile: PerformanceProfile) {
        val refreshedState = runBlocking { container.repository.observeState().first() }
        val settings = runBlocking { container.settingsStorage.settings.first() }
        val presentation = buildTilePresentation(refreshedState, settings)
        qsTile?.apply {
            label = presentation.label
            subtitle = presentation.subtitle
            state = if (profile.id == ProfileStateResolver.STOCK_PROFILE_ID || profile.name == "Stock") {
                Tile.STATE_INACTIVE
            } else {
                Tile.STATE_ACTIVE
            }
            updateTile()
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun launchDialogAndCollapse() {
        val intent = TileControlActivity.createDialogIntent(applicationContext)
        launchIntentAndCollapse(intent)
    }

    @Suppress("DEPRECATION")
    private fun launchAppAndCollapse() {
        val intent = Intent(applicationContext, com.aure.clustertune.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        launchIntentAndCollapse(intent)
    }

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
