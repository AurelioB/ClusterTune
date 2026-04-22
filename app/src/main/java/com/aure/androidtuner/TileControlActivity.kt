package com.aure.androidtuner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aure.androidtuner.ui.TunerScreen
import com.aure.androidtuner.ui.TunerViewModel

class TileControlActivity : ComponentActivity() {

    private val container by lazy { AppContainer(this) }
    private val viewModel by viewModels<TunerViewModel> {
        TunerViewModel.factory(container.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Surface {
                    val state = viewModel.state.collectAsStateWithLifecycle().value
                    TunerScreen(
                        state = state,
                        onPolicyValueChange = viewModel::setPolicyValue,
                        onApplyProfile = viewModel::applyProfile,
                        onClearSelection = viewModel::clearSelection,
                        onApplyCurrent = viewModel::applyCurrent,
                        onSavePreset = viewModel::saveCurrentAsPreset,
                        compactMode = true,
                        onDismissRequest = ::finish,
                    )
                }
            }
        }
    }
}
