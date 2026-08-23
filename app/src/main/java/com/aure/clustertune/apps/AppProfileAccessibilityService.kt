package com.aure.clustertune.apps

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import androidx.core.content.ContextCompat
import com.aure.clustertune.AppContainer

/** Event-driven source of visible application windows across all displays. */
class AppProfileAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var suspended = false
    private var receiverRegistered = false
    private var displayListenerRegistered = false
    private var coordinator: AppProfileCoordinator? = null
    private val fallbackPackagesByDisplay = mutableMapOf<Int, String>()
    private val disappearanceTracker = VisibleWindowDisappearanceTracker(ABSENCE_CONFIRMATION_DELAY_MS)
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val refresh = Runnable { publishSnapshot() }
    private val absenceConfirmation = Runnable {
        absenceConfirmationScheduledAt = null
        publishSnapshot()
    }
    private var absenceConfirmationScheduledAt: Long? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleRefresh()

        override fun onDisplayChanged(displayId: Int) = scheduleRefresh()

        override fun onDisplayRemoved(displayId: Int) {
            fallbackPackagesByDisplay.remove(displayId)
            disappearanceTracker.removeDisplay(displayId)
            scheduleRefresh()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    suspended = true
                    enterSuspendedState()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    suspended = false
                    scheduleRefresh()
                }
            }
        }
    }

    override fun onServiceConnected() {
        suspended = getSystemService(PowerManager::class.java)?.isInteractive == false
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).also {
            it.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            it.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            it.flags = it.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        if (!displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
        }
        if (coordinator == null) {
            val container = AppContainer(this)
            coordinator = AppProfileCoordinator(
                context = applicationContext,
                repository = container.repository,
                profileStorage = container.profileStorage,
                settingsStorage = container.settingsStorage,
            ).also(AppProfileCoordinator::start)
        }
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (suspended) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.toString()?.takeIf(::isUsefulFallbackPackage)?.let { packageName ->
                    val eventDisplayId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        event.displayId.takeIf { it >= 0 } ?: Display.DEFAULT_DISPLAY
                    } else {
                        Display.DEFAULT_DISPLAY
                    }
                    fallbackPackagesByDisplay[eventDisplayId] = packageName
                }
            }
            scheduleRefresh()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(absenceConfirmation)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        coordinator?.stop()
        coordinator = null
        fallbackPackagesByDisplay.clear()
        disappearanceTracker.clear()
        VisibleAppWindowEvents.clear(isInteractive = false)
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, COALESCE_DELAY_MS)
    }

    private fun publishSnapshot() {
        if (suspended || getSystemService(PowerManager::class.java)?.isInteractive == false) {
            suspended = true
            enterSuspendedState()
            return
        }
        val byDisplay = mutableMapOf<Int, MutableList<VisibleAppWindow>>()
        val allDisplays = windowsOnAllDisplays
        for (displayIndex in 0 until allDisplays.size()) {
            val displayId = allDisplays.keyAt(displayIndex)
            allDisplays.valueAt(displayIndex).orEmpty().forEach { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
                val packageName = window.root?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return@forEach
                val item = VisibleAppWindow(packageName, displayId, window.isFocused, window.isActive)
                byDisplay.getOrPut(displayId) { mutableListOf() }.add(item)
            }
        }
        val withEventFallbacks = mergeEventFallbackWindows(
            observed = byDisplay,
            eventFallbacks = fallbackPackagesByDisplay,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )
        val tracked = disappearanceTracker.stabilize(
            withEventFallbacks,
            ::isDisplayOn,
            android.os.SystemClock.uptimeMillis(),
        )
        val normalized = tracked.windowsByDisplay.mapValues { (_, items) ->
            items.distinct().sortedWith(compareBy({ it.packageName }, { it.isFocused.not() }, { it.isActive.not() }))
        }.filterValues { it.isNotEmpty() }.toSortedMap()
        val scheduledAt = absenceConfirmationScheduledAt
        val confirmationAt = tracked.nextDeadlineMs
        if (confirmationAt == null) {
            handler.removeCallbacks(absenceConfirmation)
            absenceConfirmationScheduledAt = null
        } else if (scheduledAt == null || confirmationAt < scheduledAt) {
            handler.removeCallbacks(absenceConfirmation)
            handler.postAtTime(absenceConfirmation, confirmationAt)
            absenceConfirmationScheduledAt = confirmationAt
        }
        VisibleAppWindowEvents.publish(VisibleAppSnapshot(normalized, isInteractive = true))
    }

    private fun isDisplayOn(displayId: Int): Boolean =
        displayManager?.getDisplay(displayId)?.state == Display.STATE_ON

    private fun enterSuspendedState() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(absenceConfirmation)
        absenceConfirmationScheduledAt = null
        fallbackPackagesByDisplay.clear()
        disappearanceTracker.pause()
        VisibleAppWindowEvents.clear(isInteractive = false)
    }

    private fun isUsefulFallbackPackage(packageName: String): Boolean {
        if (packageName.isBlank() ||
            packageName == this.packageName ||
            packageName in TRANSIENT_PACKAGES ||
            packageName in VENDOR_GAME_ASSISTANT_PACKAGES
        ) {
            return false
        }
        val inputMethodPackage = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
        return packageName != inputMethodPackage
    }

    companion object {
        private const val COALESCE_DELAY_MS = 50L
        private const val ABSENCE_CONFIRMATION_DELAY_MS = 500L
        private val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
