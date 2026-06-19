package com.aure.clustertune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.CompactTunerScreen
import com.aure.clustertune.ui.TunerViewModel
import com.aure.clustertune.ui.theme.ClusterTuneTheme
import kotlinx.coroutines.launch

class TileControlActivity : ComponentActivity() {

    companion object {
        private const val ACTION_OPEN_DIALOG = "com.aure.clustertune.action.OPEN_TILE_DIALOG"
        private const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        fun createDialogIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_OPEN_DIALOG
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }
    }

    private val container by lazy { AppContainer(this) }
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(
            repository = container.repository,
            settingsStorage = container.settingsStorage,
            odinScriptHandoff = container.odinScriptHandoff,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val launchedFromLongPress = intent?.action == ACTION_QS_TILE_PREFERENCES
        if (launchedFromLongPress) {
            openFullApp()
            return
        }

        setEditorContent()
    }

    private fun setEditorContent() {
        setContent {
            val settings = viewModel.settings.collectAsStateWithLifecycle().value
            ClusterTuneTheme(settings = settings) {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    CompactTunerScreen(
                        state = state,
                        onPolicyValueChange = viewModel::setPolicyValue,
                        onApplyProfile = viewModel::applyProfile,
                        onClearSelection = viewModel::clearSelection,
                        onApplyCurrent = { tunerState ->
                            applyCurrentFromDialog(tunerState)
                        },
                        onDismissRequest = ::dismissTileDialog,
                        onRefreshLiveValues = viewModel::refreshLiveState,
                        onOpenFullApp = {
                            openFullApp()
                        },
                    )
                }
            }
        }
    }

    private fun applyCurrentFromDialog(state: TunerState) {
        lifecycleScope.launch {
            val appliedProfile = ProfileStateResolver.preferredProfileForCurrentValues(state)
            val result = container.repository.applyValues(
                policies = state.policies,
                selectedValues = state.currentValues,
                isReset = appliedProfile?.id == ProfileStateResolver.STOCK_PROFILE_ID,
                appliedDisplayProfileId = appliedProfile?.id ?: ProfileStateResolver.MANUAL_PROFILE_ID,
            )
            result.onSuccess {
                container.repository.selectProfile(
                    appliedProfile?.id?.takeUnless { id -> id == ProfileStateResolver.STOCK_PROFILE_ID },
                )
                Toast.makeText(
                    applicationContext,
                    "Applied ${appliedProfile?.name ?: "Manual"}",
                    Toast.LENGTH_SHORT,
                ).show()
                dismissTileDialog()
                QuickSettingsTileRefresher.requestUpdate(applicationContext)
            }.onFailure { throwable ->
                Toast.makeText(
                    applicationContext,
                    throwable.message ?: "Failed to apply limits",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openFullApp() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun dismissTileDialog() {
        finishAndRemoveTask()
    }
}
