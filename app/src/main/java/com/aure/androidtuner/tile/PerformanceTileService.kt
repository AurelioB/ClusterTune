package com.aure.androidtuner.tile

import android.util.Log
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import com.aure.androidtuner.AppContainer
import com.aure.androidtuner.R
import com.aure.androidtuner.model.TunerState
import com.aure.androidtuner.ui.TunerScreen
import com.aure.androidtuner.ui.formatFrequency
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

class PerformanceTileService : TileService() {

    companion object {
        private const val TAG = "PerformanceTile"
    }

    override fun onStartListening() {
        super.onStartListening()
        runCatching {
            val repository = AppContainer(applicationContext).repository
            val state = runBlocking { repository.observeState().first() }
            qsTile?.apply {
                label = "Performance"
                subtitle = if (state.isPServerAvailable) {
                    "${state.policies.size} policies"
                } else {
                    "Unavailable"
                }
                this.state = if (state.isPServerAvailable) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
                updateTile()
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to refresh tile state", throwable)
            qsTile?.apply {
                label = "Performance"
                subtitle = "Error"
                state = Tile.STATE_UNAVAILABLE
                updateTile()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun(::showControlsDialog)
        } else {
            showControlsDialog()
        }
    }

    private fun showControlsDialog() {
        runCatching {
            val repository = AppContainer(applicationContext).repository
            val dialog = ComponentDialog(this, R.style.Theme_AndroidTuner_TileDialog)
            dialog.setContentView(
                ComposeView(this).apply {
                    setContent {
                        MaterialTheme {
                            Surface {
                                val repoState by repository.observeState().collectAsState(initial = TunerState())
                                var edits by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
                                var statusMessage by remember { mutableStateOf<String?>(null) }
                                var errorMessage by remember { mutableStateOf<String?>(null) }
                                val scope = rememberCoroutineScope()

                                val state = repoState.copy(
                                    currentValues = repoState.currentValues + edits,
                                    statusMessage = statusMessage,
                                    errorMessage = errorMessage,
                                )

                                TunerScreen(
                                    state = state,
                                    onPolicyValueChange = { policy, rawValue ->
                                        val snapped = policy.supportedFrequencies.minByOrNull { abs(it - rawValue) } ?: rawValue
                                        val updatedEdits = edits + (policy.id to snapped)
                                        edits = updatedEdits
                                        statusMessage = null
                                        errorMessage = null
                                        val baseValues = repoState.policies.associate { cpuPolicy ->
                                            cpuPolicy.id to (repoState.actualValues[cpuPolicy.id] ?: cpuPolicy.currentMaxFreq)
                                        }
                                        val pendingValues = baseValues + updatedEdits
                                        val selectedProfile = (repoState.bundledProfiles + repoState.userProfiles)
                                            .firstOrNull { it.id == repoState.selectedProfileId }
                                        if (selectedProfile != null) {
                                            val stillMatches = selectedProfile.maxFrequencies.isNotEmpty() &&
                                                selectedProfile.maxFrequencies.all { (policyId, value) ->
                                                    pendingValues[policyId] == value
                                                }
                                            if (!stillMatches) {
                                                scope.launch {
                                                    repository.selectProfile(null)
                                                }
                                            }
                                        }
                                    },
                                    onApplyProfile = { profile ->
                                        edits = edits + profile.maxFrequencies
                                        statusMessage = null
                                        errorMessage = null
                                        scope.launch {
                                            repository.selectProfile(profile.id)
                                        }
                                    },
                                    onClearSelection = {},
                                    onApplyCurrent = { currentState ->
                                        statusMessage = null
                                        errorMessage = null
                                        scope.launch {
                                            val selectedProfile = (currentState.bundledProfiles + currentState.userProfiles)
                                                .firstOrNull { it.id == currentState.selectedProfileId }
                                            repository.applyValues(
                                                policies = currentState.policies,
                                                selectedValues = currentState.currentValues,
                                                isReset = selectedProfile?.isResetProfile == true,
                                            ).onSuccess { outcome ->
                                                edits = emptyMap()
                                                repository.refresh()
                                                if (outcome.verificationPassed) {
                                                    statusMessage = selectedProfile?.let { "Applied preset: ${it.name}" }
                                                        ?: "Applied manual settings"
                                                } else {
                                                    val summary = currentState.policies.joinToString(", ") { policy ->
                                                        val requested = currentState.currentValues[policy.id] ?: policy.currentMaxFreq
                                                        val actual = outcome.actualValues[policy.id] ?: policy.currentMaxFreq
                                                        "P${policy.id} ${formatFrequency(requested)} -> ${formatFrequency(actual)}"
                                                    }
                                                    errorMessage = "Apply did not stick: $summary"
                                                }
                                            }.onFailure { throwable ->
                                                errorMessage = throwable.message ?: "Failed to apply limits"
                                            }
                                        }
                                    },
                                    onSavePreset = { _, _ -> },
                                    compactMode = true,
                                    onDismissRequest = { dialog.dismiss() },
                                )
                            }
                        }
                    }
                },
            )
            showDialog(dialog)
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to show tile dialog", throwable)
        }
    }
}
