package com.aure.clustertune.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.aure.clustertune.apps.nominalDisplayRefreshRateFps

/** Observes the active mode of the display hosting the current Compose window. */
@Composable
internal fun rememberCurrentDisplayRefreshRateFps(): Int? {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val displayManager = remember(context) { context.getSystemService(DisplayManager::class.java) }
    val displayId = view.display?.displayId ?: Display.DEFAULT_DISPLAY

    fun readCurrentRate(): Int? = displayManager
        ?.getDisplay(displayId)
        ?.refreshRate
        ?.let(::nominalDisplayRefreshRateFps)

    var refreshRateFps by remember(displayManager, displayId) {
        mutableStateOf(readCurrentRate())
    }
    DisposableEffect(displayManager, displayId) {
        if (displayManager == null) return@DisposableEffect onDispose { }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(changedDisplayId: Int) {
                if (changedDisplayId == displayId) refreshRateFps = readCurrentRate()
            }

            override fun onDisplayChanged(changedDisplayId: Int) {
                if (changedDisplayId == displayId) refreshRateFps = readCurrentRate()
            }

            override fun onDisplayRemoved(changedDisplayId: Int) {
                if (changedDisplayId == displayId) refreshRateFps = null
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        refreshRateFps = readCurrentRate()
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }
    return refreshRateFps
}
