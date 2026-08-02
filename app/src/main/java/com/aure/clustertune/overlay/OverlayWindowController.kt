package com.aure.clustertune.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import kotlin.math.roundToInt

enum class OverlayType {
    COMPACT_PROFILE_PICKER,
}

data class EdgeHandleWindowConfig(
    val heightDp: Int,
    val verticalPositionPercent: Int,
)

class OverlayWindowController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService<WindowManager>()
        ?: error("WindowManager unavailable")
    private var modalView: View? = null
    private var modalType: OverlayType? = null
    private var modalBackHandler: ModalOverlayBackHandler? = null
    private var edgeHandleView: View? = null
    private var edgeHandleAttached = false
    private var edgeHandleConfig: EdgeHandleWindowConfig? = null

    val hasActiveOverlay: Boolean
        get() = modalView != null || edgeHandleView != null

    fun isShowing(type: OverlayType): Boolean = modalType == type && modalView != null

    fun show(
        type: OverlayType,
        view: View,
        onBackPressed: () -> Unit,
    ) {
        dismissModal(restoreEdgeHandle = false)
        detachEdgeHandle()
        val backHandler = ModalOverlayBackHandler(view, onBackPressed)
        try {
            backHandler.install()
            windowManager.addView(view, modalLayoutParams())
            modalType = type
            modalView = view
            modalBackHandler = backHandler
        } catch (throwable: Throwable) {
            backHandler.dispose()
            runCatching { windowManager.removeView(view) }
            attachEdgeHandle()
            throw throwable
        }
    }

    fun dismiss(type: OverlayType? = null) {
        if (type != null && modalType != type) return
        dismissModal(restoreEdgeHandle = true)
    }

    fun showEdgeHandle(view: View, config: EdgeHandleWindowConfig) {
        removeEdgeHandle()
        edgeHandleView = view
        edgeHandleConfig = config
        attachEdgeHandle()
    }

    fun removeEdgeHandle() {
        detachEdgeHandle()
        edgeHandleView = null
        edgeHandleConfig = null
    }

    fun refreshEdgeHandleLayout() {
        val view = edgeHandleView ?: return
        val config = edgeHandleConfig ?: return
        if (!edgeHandleAttached) return
        runCatching {
            windowManager.updateViewLayout(view, edgeHandleLayoutParams(config))
        }
    }

    fun updateEdgeHandleConfig(config: EdgeHandleWindowConfig) {
        if (edgeHandleView == null) return
        edgeHandleConfig = config
        refreshEdgeHandleLayout()
    }

    fun dismissAll() {
        dismissModal(restoreEdgeHandle = false)
        removeEdgeHandle()
    }

    private fun dismissModal(restoreEdgeHandle: Boolean) {
        modalBackHandler?.dispose()
        modalBackHandler = null
        modalView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        modalView = null
        modalType = null
        if (restoreEdgeHandle) {
            attachEdgeHandle()
        }
    }

    private fun attachEdgeHandle() {
        val view = edgeHandleView ?: return
        val config = edgeHandleConfig ?: return
        if (edgeHandleAttached || modalView != null) return
        try {
            windowManager.addView(view, edgeHandleLayoutParams(config))
            edgeHandleAttached = true
        } catch (throwable: Throwable) {
            edgeHandleView = null
            edgeHandleConfig = null
            throw throwable
        }
    }

    private fun detachEdgeHandle() {
        val view = edgeHandleView ?: return
        if (!edgeHandleAttached) return
        runCatching { windowManager.removeView(view) }
        edgeHandleAttached = false
    }

    private fun modalLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            modalWindowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            title = "ClusterTune overlay"
            windowAnimations = android.R.style.Animation
            alpha = 1f
            dimAmount = 0f
            flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun edgeHandleLayoutParams(config: EdgeHandleWindowConfig): WindowManager.LayoutParams {
        val heightPx = dp(config.heightDp)
        val displayHeightPx = windowManager.currentWindowMetrics.bounds.height()
        return WindowManager.LayoutParams(
            dp(28),
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
            y = calculateEdgeHandleTopOffset(
                displayHeightPx = displayHeightPx,
                handleHeightPx = heightPx,
                verticalPositionPercent = config.verticalPositionPercent,
            )
            title = "ClusterTune profile edge handle"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * appContext.resources.displayMetrics.density).toInt()
    }
}

private class ModalOverlayBackHandler(
    private val view: View,
    private val onBackPressed: () -> Unit,
) : View.OnAttachStateChangeListener {
    private var predictiveBackRegistration: PredictiveBackRegistration? = null

    fun install() {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) {
                false
            } else {
                if (
                    shouldDismissOverlayOnKeyEvent(
                        keyCode = keyCode,
                        action = event.action,
                        canceled = event.isCanceled,
                    )
                ) {
                    onBackPressed()
                }
                true
            }
        }
        view.addOnAttachStateChangeListener(this)
    }

    fun dispose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            predictiveBackRegistration?.dispose()
        }
        predictiveBackRegistration = null
        view.setOnKeyListener(null)
        view.removeOnAttachStateChangeListener(this)
    }

    override fun onViewAttachedToWindow(attachedView: View) {
        attachedView.requestFocus()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            predictiveBackRegistration == null
        ) {
            predictiveBackRegistration = PredictiveBackRegistration.register(
                view = attachedView,
                onBackPressed = onBackPressed,
            )
        }
    }

    override fun onViewDetachedFromWindow(detachedView: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            predictiveBackRegistration?.dispose()
        }
        predictiveBackRegistration = null
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class PredictiveBackRegistration private constructor(
    private val dispatcher: OnBackInvokedDispatcher,
    private val callback: OnBackInvokedCallback,
) {
    fun dispose() {
        dispatcher.unregisterOnBackInvokedCallback(callback)
    }

    companion object {
        fun register(
            view: View,
            onBackPressed: () -> Unit,
        ): PredictiveBackRegistration? {
            val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
            val callback = OnBackInvokedCallback(onBackPressed)
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
            return PredictiveBackRegistration(dispatcher, callback)
        }
    }
}

internal fun shouldDismissOverlayOnKeyEvent(
    keyCode: Int,
    action: Int,
    canceled: Boolean,
): Boolean {
    return keyCode == KeyEvent.KEYCODE_BACK &&
        action == KeyEvent.ACTION_UP &&
        !canceled
}

internal fun modalWindowFlags(): Int {
    return WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
}

internal fun calculateEdgeHandleTopOffset(
    displayHeightPx: Int,
    handleHeightPx: Int,
    verticalPositionPercent: Int,
): Int {
    val availableTravelPx = (displayHeightPx - handleHeightPx).coerceAtLeast(0)
    return (
        availableTravelPx *
            verticalPositionPercent.coerceIn(0, 100) / 100f
        ).roundToInt()
}
