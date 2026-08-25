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
    private val packagesByAccessibilityWindow = AccessibilityWindowPackageCache()
    private var mostRecentAppIdentity: RecentAppIdentity? = null
    private val disappearanceTracker = VisibleWindowDisappearanceTracker(ABSENCE_CONFIRMATION_DELAY_MS)
    private val pickerHandoffTracker = PickerForegroundAppHandoffTracker(
        seedDurationMs = PICKER_HANDOFF_SEED_DURATION_MS,
        leaseDurationMs = PICKER_FOREGROUND_LEASE_DURATION_MS,
    )
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val refresh = Runnable { publishSnapshot() }
    private val absenceConfirmation = Runnable {
        absenceConfirmationScheduledAt = null
        publishSnapshot()
    }
    private val settledRefresh = Runnable {
        if (!suspended) publishSnapshot()
    }
    private var absenceConfirmationScheduledAt: Long? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleRefresh()

        override fun onDisplayChanged(displayId: Int) = scheduleRefresh()

        override fun onDisplayRemoved(displayId: Int) {
            fallbackPackagesByDisplay.remove(displayId)
            packagesByAccessibilityWindow.removeDisplay(displayId)
            if (mostRecentAppIdentity?.displayId == displayId) {
                mostRecentAppIdentity = null
            }
            disappearanceTracker.removeDisplay(displayId)
            pickerHandoffTracker.removeDisplay(displayId)
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
                    scheduleSettledRefreshes()
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
        scheduleSettledRefreshes()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (suspended) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { packageName ->
                    val reportedDisplayId = reportedDisplayId(event)
                    val windowIdsByDisplay = currentWindowIdsByDisplay()
                    val eventDisplayId = resolveAccessibilityEventDisplayId(
                        reportedDisplayId = reportedDisplayId,
                        eventWindowId = event.windowId,
                        windowIdsByDisplay = windowIdsByDisplay,
                        defaultDisplayId = Display.DEFAULT_DISPLAY,
                    )
                    val identity = resolveAccessibilityEventWindowIdentity(
                        reportedDisplayId = reportedDisplayId,
                        eventWindowId = event.windowId,
                        windowIdsByDisplay = windowIdsByDisplay,
                    )
                    if (isUsefulFallbackPackage(packageName)) {
                        fallbackPackagesByDisplay[eventDisplayId] = packageName
                        mostRecentAppIdentity = RecentAppIdentity(eventDisplayId, packageName)
                        identity?.let { packagesByAccessibilityWindow.record(it, packageName) }
                        scheduleSettledRefreshes()
                    } else {
                        identity?.let(packagesByAccessibilityWindow::remove)
                    }
                }
            }
            scheduleRefresh()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(absenceConfirmation)
        handler.removeCallbacks(settledRefresh)
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
        packagesByAccessibilityWindow.clear()
        mostRecentAppIdentity = null
        disappearanceTracker.clear()
        pickerHandoffTracker.clear()
        VisibleAppWindowEvents.clear(isInteractive = false)
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, COALESCE_DELAY_MS)
    }

    /**
     * Some SurfaceView-heavy apps expose their root shortly after the first
     * window event or service reconnect. These two bounded follow-up samples
     * seed exact-window picker provenance without changing disappearance grace.
     */
    private fun scheduleSettledRefreshes() {
        if (suspended) return
        handler.removeCallbacks(settledRefresh)
        SETTLED_REFRESH_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed(settledRefresh, delayMs)
        }
    }

    private fun publishSnapshot() {
        if (suspended || getSystemService(PowerManager::class.java)?.isInteractive == false) {
            suspended = true
            enterSuspendedState()
            return
        }
        val byDisplay = mutableMapOf<Int, MutableList<VisibleAppWindow>>()
        val usefulFallbackCandidatesByDisplay = mutableMapOf<Int, MutableList<VisibleAppWindow>>()
        val observedWindowIdentities = mutableSetOf<AccessibilityWindowIdentity>()
        val allDisplays = windowsOnAllDisplays
        for (displayIndex in 0 until allDisplays.size()) {
            val displayId = allDisplays.keyAt(displayIndex)
            allDisplays.valueAt(displayIndex).orEmpty().forEach { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return@forEach
                }
                val identity = window.id.takeIf { it >= 0 }?.let { windowId ->
                    AccessibilityWindowIdentity(displayId, windowId)
                }
                identity?.let(observedWindowIdentities::add)
                val resolvedPackageName = window.root?.packageName?.toString()?.takeIf { it.isNotBlank() }
                val usefulResolvedPackageName = resolvedPackageName?.takeIf(::isUsefulFallbackPackage)
                if (resolvedPackageName != null) {
                    identity?.let { resolvedIdentity ->
                        if (usefulResolvedPackageName != null) {
                            packagesByAccessibilityWindow.record(resolvedIdentity, usefulResolvedPackageName)
                        } else {
                            packagesByAccessibilityWindow.remove(resolvedIdentity)
                        }
                    }
                }
                val packageName = packagesByAccessibilityWindow.resolvePackage(
                    identity = identity,
                    resolvedPackageName = resolvedPackageName,
                ) ?: return@forEach
                val item = VisibleAppWindow(packageName, displayId, window.isFocused, window.isActive)
                byDisplay.getOrPut(displayId) { mutableListOf() }.add(item)
                if (usefulResolvedPackageName != null) {
                    usefulFallbackCandidatesByDisplay
                        .getOrPut(displayId) { mutableListOf() }
                        .add(item)
                }
            }
        }
        packagesByAccessibilityWindow.retainOnly(observedWindowIdentities)
        usefulFallbackCandidatesByDisplay.forEach { (displayId, candidates) ->
            selectObservedFallbackPackage(
                candidates = candidates,
                existingPackageName = fallbackPackagesByDisplay[displayId],
            )?.let { packageName ->
                // Accessibility may reconnect without first delivering a
                // TYPE_WINDOW_STATE_CHANGED event for the running app.
                fallbackPackagesByDisplay[displayId] = packageName
            }
        }
        val nowMs = android.os.SystemClock.uptimeMillis()
        val verifiedPickerPackagesByDisplay = byDisplay.mapNotNull { (displayId, windows) ->
            selectObservedFallbackPackage(
                // Cached packages remain tied to exact TYPE_APPLICATION window
                // identities that are present in this enumeration. Once an
                // identity disappears, retainOnly() drops it and the fixed
                // picker handoff begins; empty snapshots cannot renew it.
                candidates = windows.filter { window ->
                    isUsefulFallbackPackage(window.packageName)
                },
                existingPackageName = fallbackPackagesByDisplay[displayId],
            )?.let { packageName -> displayId to packageName }
        }.toMap()
        val pickerHandoffs = pickerHandoffTracker.update(
            verifiedPackageByDisplay = verifiedPickerPackagesByDisplay,
            displayOn = ::isDisplayOn,
            nowMs = nowMs,
        )
        val tracked = disappearanceTracker.stabilize(
            observed = byDisplay,
            displayOn = ::isDisplayOn,
            nowMs = nowMs,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )
        val normalized = tracked.windowsByDisplay.mapValues { (_, items) ->
            items.distinct().sortedWith(compareBy({ it.packageName }, { it.isFocused.not() }, { it.isActive.not() }))
        }.filterValues { it.isNotEmpty() }.toSortedMap()
        val refreshRateFpsByDisplay = displayManager?.displays
            .orEmpty()
            .mapNotNull { display ->
                nominalDisplayRefreshRateFps(display.refreshRate)?.let { refreshRateFps ->
                    display.displayId to refreshRateFps
                }
            }
            .toMap()
            .toSortedMap()
        fallbackPackagesByDisplay.entries.removeAll { (displayId, packageName) ->
            normalized[displayId].orEmpty().none { window ->
                window.packageName == packageName
            }
        }
        mostRecentAppIdentity = mostRecentAppIdentity?.takeIf { identity ->
            normalized[identity.displayId].orEmpty().any { window ->
                window.packageName == identity.packageName
            }
        }
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
        VisibleAppWindowEvents.publish(
            VisibleAppSnapshot(
                windowsByDisplay = normalized,
                isInteractive = true,
                recentPackageByDisplay = fallbackPackagesByDisplay.filterKeys(normalized::containsKey),
                mostRecentAppIdentity = mostRecentAppIdentity,
                refreshRateFpsByDisplay = refreshRateFpsByDisplay,
                pickerHandoffByDisplay = pickerHandoffs,
            ),
        )
    }

    private fun isDisplayOn(displayId: Int): Boolean =
        displayManager?.getDisplay(displayId)?.state == Display.STATE_ON

    private fun reportedDisplayId(event: AccessibilityEvent): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            event.displayId
        } else {
            null
        }

    private fun currentWindowIdsByDisplay(): Map<Int, List<Int>> = buildMap {
        val allDisplays = windowsOnAllDisplays
        for (displayIndex in 0 until allDisplays.size()) {
            val displayId = allDisplays.keyAt(displayIndex)
            put(displayId, allDisplays.valueAt(displayIndex).orEmpty().map { it.id })
        }
    }

    private fun enterSuspendedState() {
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(absenceConfirmation)
        handler.removeCallbacks(settledRefresh)
        absenceConfirmationScheduledAt = null
        fallbackPackagesByDisplay.clear()
        packagesByAccessibilityWindow.clear()
        mostRecentAppIdentity = null
        disappearanceTracker.pause()
        pickerHandoffTracker.clear()
        VisibleAppWindowEvents.clear(isInteractive = false)
    }

    private fun isUsefulFallbackPackage(packageName: String): Boolean {
        if (packageName.isBlank() ||
            packageName == this.packageName ||
            packageName in TRANSIENT_APP_WINDOW_PACKAGES ||
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
        private val SETTLED_REFRESH_DELAYS_MS = longArrayOf(250L, 750L)
        private const val PICKER_HANDOFF_SEED_DURATION_MS = 2_000L
    }
}

internal fun resolveAccessibilityEventDisplayId(
    reportedDisplayId: Int?,
    eventWindowId: Int,
    windowIdsByDisplay: Map<Int, List<Int>>,
    defaultDisplayId: Int = 0,
): Int {
    reportedDisplayId?.takeIf { it >= 0 }?.let { return it }
    if (eventWindowId >= 0) {
        windowIdsByDisplay.toSortedMap().entries.firstOrNull { (_, windowIds) ->
            eventWindowId in windowIds
        }?.let { return it.key }
    }
    return defaultDisplayId
}

internal fun resolveAccessibilityEventWindowIdentity(
    reportedDisplayId: Int?,
    eventWindowId: Int,
    windowIdsByDisplay: Map<Int, List<Int>>,
): AccessibilityWindowIdentity? {
    if (eventWindowId < 0) return null
    val displayId = reportedDisplayId?.takeIf { it >= 0 }
        ?: windowIdsByDisplay.toSortedMap().entries.firstOrNull { (_, windowIds) ->
            eventWindowId in windowIds
        }?.key
        ?: return null
    return AccessibilityWindowIdentity(displayId, eventWindowId)
}
