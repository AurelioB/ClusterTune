package com.aure.clustertune

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.aure.clustertune.overlay.OverlayHostService

class TileControlActivity : ComponentActivity() {

    companion object {
        private const val ACTION_SHOW_COMPACT_TUNER_OVERLAY =
            "com.aure.clustertune.action.SHOW_COMPACT_TUNER_OVERLAY"
        private const val ACTION_SHOW_PROFILE_PICKER_OVERLAY =
            "com.aure.clustertune.action.SHOW_PROFILE_PICKER_OVERLAY"
        private const val ACTION_QS_TILE_PREFERENCES = "android.service.quicksettings.action.QS_TILE_PREFERENCES"

        fun createCompactTunerOverlayIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_SHOW_COMPACT_TUNER_OVERLAY
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }

        fun createProfilePickerOverlayIntent(context: Context): Intent {
            return Intent(context, TileControlActivity::class.java).apply {
                action = ACTION_SHOW_PROFILE_PICKER_OVERLAY
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launchedFromLongPress = intent?.action == ACTION_QS_TILE_PREFERENCES
        if (launchedFromLongPress) {
            openFullApp()
            return
        }

        when (intent?.action) {
            ACTION_SHOW_COMPACT_TUNER_OVERLAY -> {
                OverlayHostService.showCompactTuner(applicationContext)
                finishAndRemoveTask()
            }
            ACTION_SHOW_PROFILE_PICKER_OVERLAY -> {
                OverlayHostService.showProfilePicker(applicationContext)
                finishAndRemoveTask()
            }
            else -> finishAndRemoveTask()
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
}
