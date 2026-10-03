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
import android.util.Log
import android.util.SparseArray
import android.view.Display
import androidx.core.content.ContextCompat
import com.aure.clustertune.AppContainer
import com.aure.clustertune.permissions.AppProfileAccessibilityAccess

/** Event-driven source of visible application windows across all displays. */
open class AppProfileAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var initialized = false
    private var suspended = false
    private var container: AppContainer? = null
    private var inputMethodPackage: String? = null
    private var receiverRegistered = false
    private var displayListenerRegistered = false
    private var coordinator: AppProfileCoordinator? = null
    private val fallbackPackagesByDisplay = mutableMapOf<Int, String>()
    private val packagesByAccessibilityWindow = AccessibilityWindowPackageCache()
    private var mostRecentAppIdentity: RecentAppIdentity? = null
    private val disappearanceTracker = VisibleWindowDisappearanceTracker(ABSENCE_CONFIRMATION_DELAY_MS)
    private val pickerPackageTracker = PickerForegroundPackageTracker()
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val snapshotRecovery: AccessibilityCallbackRecovery = AccessibilityCallbackRecovery(
        schedule = { task, delay -> handler.postDelayed(task, delay) },
        cancel = handler::removeCallbacks,
        onFailure = { error ->
            Log.e(TAG, "Unable to read accessibility windows; clearing stale app ownership", error)
            AppProfileAccessibilityAccess.setHealthy(false)
            handler.removeCallbacks(absenceConfirmation)
            absenceConfirmationScheduledAt = null
            clearWindowState()
        },
        action = { publishSnapshot() },
    )
    private val initializationRecovery: AccessibilityCallbackRecovery = AccessibilityCallbackRecovery(
        schedule = { task, delay -> handler.postDelayed(task, delay) },
        cancel = handler::removeCallbacks,
        onFailure = { error ->
            Log.e(TAG, "Accessibility initialization failed", error)
            initialized = false
            enterSuspendedState()
            stopCoordinator()
            AppProfileAccessibilityAccess.setHealthy(false)
        },
        action = { initializeConnection() },
    )
    private val refresh = Runnable { snapshotRecovery.run() }
    private val absenceConfirmation: Runnable = Runnable {
        absenceConfirmationScheduledAt = null
        snapshotRecovery.run()
    }
    private val settledRefresh = Runnable {
        if (bound && initialized && !suspended) snapshotRecovery.run()
    }
    private var absenceConfirmationScheduledAt: Long? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleRefresh()

        override fun onDisplayChanged(displayId: Int) = scheduleRefresh()

        override fun onDisplayRemoved(displayId: Int) {
            if (!bound || !initialized) return
            fallbackPackagesByDisplay.remove(displayId)
            packagesByAccessibilityWindow.removeDisplay(displayId)
            if (mostRecentAppIdentity?.displayId == displayId) {
                mostRecentAppIdentity = null
            }
            disappearanceTracker.removeDisplay(displayId)
            pickerPackageTracker.removeDisplay(displayId)
            scheduleRefresh()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!bound || !initialized) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    suspended = true
                    enterSuspendedState()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    suspended = false
                    snapshotRecovery.start()
                    scheduleRefresh()
                    scheduleSettledRefreshes()
                }
            }
        }
    }

    override fun onServiceConnected() {
        bound = true
        AppProfileAccessibilityAccess.setConnected(true)
        initializationRecovery.start()
        initializationRecovery.run()
    }

    private fun initializeConnection() {
        if (!bound) return
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
            val dependencies = container ?: AppContainer(this).also { container = it }
            lateinit var worker: AppProfileCoordinator
            worker = AppProfileCoordinator(
                context = applicationContext,
                repository = dependencies.repository,
                profileStorage = dependencies.profileStorage,
                settingsStorage = dependencies.settingsStorage,
                onFailure = { error ->
                    handler.post {
                        if (bound && coordinator === worker) {
                            Log.e(TAG, "App-profile worker stopped; automation needs attention", error)
                            AppProfileAccessibilityAccess.setHealthy(false)
                            stopCoordinator()
                        }
                    }
                },
            )
            coordinator = worker
            worker.start()
        }
        initialized = true
        snapshotRecovery.start()
        AppProfileAccessibilityAccess.setHealthy(true)
        scheduleRefresh()
        scheduleSettledRefreshes()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!bound || !initialized || suspended) return
        try {
            updateFromEvent(event)
        } catch (error: RuntimeException) {
            snapshotRecovery.failed(error)
        }
    }

    private fun updateFromEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                refreshInputMethodPackage()
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
                        // The package and display reported by the window-state event
                        // remain useful picker evidence even when this OEM exposes no
                        // TYPE_APPLICATION root in the following window enumeration.
                        // The picker-only package never enters windowsByDisplay, so automation
                        // still depends only on currently visible application windows.
                        pickerPackageTracker.recordWindowStateEvent(
                            displayId = eventDisplayId,
                            packageName = packageName,
                            nowMs = android.os.SystemClock.uptimeMillis(),
                        )
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

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        container?.close()
        container = null
        super.onDestroy()
    }

    private fun disconnect() {
        bound = false
        initialized = false
        suspended = true
        AppProfileAccessibilityAccess.setConnected(false)
        initializationRecovery.stop()
        snapshotRecovery.stop()
        enterSuspendedState()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
                .onFailure { Log.w(TAG, "Unable to unregister screen listener", it) }
            receiverRegistered = false
        }
        if (displayListenerRegistered) {
            runCatching { displayManager?.unregisterDisplayListener(displayListener) }
                .onFailure { Log.w(TAG, "Unable to unregister display listener", it) }
            displayListenerRegistered = false
        }
        stopCoordinator()
    }

    private fun stopCoordinator() {
        val previous = coordinator
        coordinator = null
        previous?.stop()
    }

    private fun scheduleRefresh() {
        if (!bound || !initialized || suspended) return
        // Coalesce without moving the deadline: a stream of window events must not starve sampling.
        if (!handler.hasCallbacks(refresh)) handler.postDelayed(refresh, COALESCE_DELAY_MS)
    }

    /**
     * Some SurfaceView-heavy apps expose their root shortly after the first
     * window event or service reconnect. These two bounded follow-up samples
     * seed exact-window picker provenance without changing disappearance grace.
     */
    private fun scheduleSettledRefreshes() {
        if (!bound || !initialized || suspended) return
        handler.removeCallbacks(settledRefresh)
        SETTLED_REFRESH_DELAYS_MS.forEach { delayMs ->
            handler.postDelayed(settledRefresh, delayMs)
        }
    }

    private fun publishSnapshot() {
        if (!bound || !initialized) return
        if (suspended || getSystemService(PowerManager::class.java)?.isInteractive == false) {
            suspended = true
            enterSuspendedState()
            return
        }
        refreshInputMethodPackage()
        val byDisplay = mutableMapOf<Int, MutableList<VisibleAppWindow>>()
        val usefulFallbackCandidatesByDisplay = mutableMapOf<Int, MutableList<VisibleAppWindow>>()
        val observedWindowIdentities = mutableSetOf<AccessibilityWindowIdentity>()
        val transientlyCoveredDisplays = mutableSetOf<Int>()
        withWindows { allDisplays ->
            for (displayIndex in 0 until allDisplays.size()) {
                val displayId = allDisplays.keyAt(displayIndex)
                if (!isDisplayOn(displayId)) {
                    fallbackPackagesByDisplay.remove(displayId)
                    packagesByAccessibilityWindow.removeDisplay(displayId)
                    disappearanceTracker.removeDisplay(displayId)
                    pickerPackageTracker.removeDisplay(displayId)
                    continue
                }
                allDisplays.valueAt(displayIndex).orEmpty().forEach { window ->
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                        isForegroundAppObscuringWindow(
                            window.title?.toString(), rootPackage(window),
                            window.isFocused, window.isActive,
                        )
                    ) {
                        transientlyCoveredDisplays.add(displayId)
                    }
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        return@forEach
                    }
                    val identity = window.id.takeIf { it >= 0 }?.let { windowId ->
                        AccessibilityWindowIdentity(displayId, windowId)
                    }
                    identity?.let(observedWindowIdentities::add)
                    val resolvedPackageName = rootPackage(window)?.takeIf { it.isNotBlank() }
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
        val verifiedPickerPackagesByDisplay = byDisplay.mapNotNull { (displayId, windows) ->
            selectObservedFallbackPackage(
                // Cached packages remain tied to exact TYPE_APPLICATION window
                // identities that are present in this enumeration. Once an
                // identity disappears, retainOnly() drops it; empty snapshots
                // cannot invent a replacement package.
                candidates = windows.filter { window ->
                    isUsefulFallbackPackage(window.packageName)
                },
                existingPackageName = fallbackPackagesByDisplay[displayId],
            )?.let { packageName -> displayId to packageName }
        }.toMap()
        val nowMs = android.os.SystemClock.uptimeMillis()
        val pickerPackages = pickerPackageTracker.updateFromEnumeration(
            verifiedPackageByDisplay = verifiedPickerPackagesByDisplay,
            displayOn = ::isDisplayOn,
            nowMs = nowMs,
        )
        val tracked = disappearanceTracker.stabilize(
            observed = byDisplay,
            displayOn = ::isDisplayOn,
            nowMs = nowMs,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
            transientlyCoveredDisplays = transientlyCoveredDisplays,
        )
        val normalized = tracked.windowsByDisplay.mapValues { (_, items) ->
            items.distinct().sortedWith(compareBy({ it.packageName }, { it.isFocused.not() }, { it.isActive.not() }))
        }.filterValues { it.isNotEmpty() }.toSortedMap()
        val refreshRateFpsByDisplay = displayManager?.displays
            .orEmpty()
            .filter { display -> display.state == Display.STATE_ON }
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
        if (coordinator != null) AppProfileAccessibilityAccess.setHealthy(true)
        VisibleAppWindowEvents.publish(
            VisibleAppSnapshot(
                windowsByDisplay = normalized,
                isInteractive = true,
                recentPackageByDisplay = fallbackPackagesByDisplay.filterKeys(normalized::containsKey),
                mostRecentAppIdentity = mostRecentAppIdentity,
                refreshRateFpsByDisplay = refreshRateFpsByDisplay,
                pickerPackageByDisplay = pickerPackages,
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

    private fun currentWindowIdsByDisplay(): Map<Int, List<Int>> = withWindows { allDisplays ->
        buildMap {
            for (displayIndex in 0 until allDisplays.size()) {
                val displayId = allDisplays.keyAt(displayIndex)
                put(displayId, allDisplays.valueAt(displayIndex).orEmpty().map { it.id })
            }
        }
    }

    /** Separate the platform read so lifecycle tests can inject disappearing-window failures. */
    protected open fun readAccessibilityWindows(): SparseArray<List<AccessibilityWindowInfo>> = windowsOnAllDisplays

    private inline fun <T> withWindows(block: (SparseArray<List<AccessibilityWindowInfo>>) -> T): T {
        val allDisplays = readAccessibilityWindows()
        try {
            return block(allDisplays)
        } finally {
            // Android 12 still pools these objects; Android 13 removed pooling.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                for (index in 0 until allDisplays.size()) {
                    allDisplays.valueAt(index).orEmpty().forEach { window ->
                        @Suppress("DEPRECATION")
                        window.recycle()
                    }
                }
            }
        }
    }

    private fun rootPackage(window: AccessibilityWindowInfo): String? {
        val root = window.root ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    private fun enterSuspendedState() {
        snapshotRecovery.stop()
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(absenceConfirmation)
        handler.removeCallbacks(settledRefresh)
        absenceConfirmationScheduledAt = null
        clearWindowState()
    }

    private fun clearWindowState() {
        fallbackPackagesByDisplay.clear()
        packagesByAccessibilityWindow.clear()
        mostRecentAppIdentity = null
        disappearanceTracker.pause()
        pickerPackageTracker.clear()
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
        return packageName != inputMethodPackage
    }

    private fun refreshInputMethodPackage() {
        inputMethodPackage = Settings.Secure.getString(
            contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
    }

    companion object {
        private const val TAG = "AppProfileAccessibility"
        private const val COALESCE_DELAY_MS = 50L
        private const val ABSENCE_CONFIRMATION_DELAY_MS = 500L
        // The last sample intentionally exceeds PickerForegroundPackageTracker's 1s event
        // protection so a genuinely newer enumerated app cannot remain suppressed forever.
        private val SETTLED_REFRESH_DELAYS_MS = longArrayOf(250L, 750L, 1_100L)
    }
}

/** Only explicit foreground controls obscure app ownership; persistent bars and HUDs do not. */
internal fun isForegroundAppObscuringWindow(
    title: String?, packageName: String?, focused: Boolean, active: Boolean,
): Boolean = (focused || active) &&
    (packageName == "com.android.systemui" || title in setOf("NotificationShade", "ClusterTune overlay"))

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
