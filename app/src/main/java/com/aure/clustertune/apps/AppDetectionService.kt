package com.aure.clustertune.apps

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import com.aure.clustertune.AppContainer
import com.aure.clustertune.notifications.AppForegroundNotification
import com.aure.clustertune.model.HistoryEventType
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.root.host.HostAppObservation
import com.aure.clustertune.root.host.HostAppSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class AppDetectionStatus(val available: Boolean = false, val error: String? = null)

object AppDetectionState {
    private val mutable = MutableStateFlow(AppDetectionStatus())
    val status = mutable.asStateFlow()
    internal fun update(available: Boolean, error: String? = null) {
        mutable.value = AppDetectionStatus(available, error)
    }
}

/** App-owned lifetime for automation; foreground-app observations come from the privileged host. */
class AppDetectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        handler.post { if (!destroyed) failed(error) }
    })
    private val container by lazy { AppContainer(this) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private var coordinator: AppProfileCoordinator? = null
    private var subscription: HostAppSubscription? = null
    private var generation = 0L
    private var destroyed = false
    private var connecting = false
    private var connectionAttemptLogged = false
    private var screenInteractive: Boolean? = null
    private var failures = 0
    private var lastObservation: HostAppObservation? = null
    private var displayConfiguration: List<Triple<Int, Int, Float>> = emptyList()
    private val reconnect = Runnable { connect() }
    private val firstObservationTimeout = Runnable {
        if (!destroyed && lastObservation == null) disconnected(IllegalStateException("App detection did not respond"))
    }
    private val sourceChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val interactive = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> false
                Intent.ACTION_SCREEN_ON -> true
                else -> null
            }
            if (interactive != null && interactive != screenInteractive) {
                screenInteractive = interactive
                recordEvent(
                    if (interactive) HistoryEventType.WAKE else HistoryEventType.SLEEP,
                    if (interactive) "Device awake" else "Device sleeping",
                    if (interactive) "Screen on; checking app profiles again." else "The screen turned off.",
                )
            }
            refresh()
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) {
            val current = displays?.displays.orEmpty().map { Triple(it.displayId, it.state, it.refreshRate) }
            if (current != displayConfiguration) {
                displayConfiguration = current
                refresh()
            }
        }
        override fun onDisplayRemoved(displayId: Int) = refresh()
    }

    override fun onCreate() {
        super.onCreate()
        AppForegroundNotification.start(this)
        ContextCompat.registerReceiver(this, sourceChanged, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        displays?.registerDisplayListener(displayListener, handler)
        scope.launch {
            container.settingsStorage.settings.map { it.privilegedExecutionMethodId }
                .distinctUntilChanged().collect { method ->
                    container.privilegedExecutionResolver.setConfiguredMethodId(method)
                    handler.post { connect() }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_RETRY, false) == true || subscription == null) connect()
        else refresh()
        return START_STICKY
    }

    private fun startCoordinator() {
        if (coordinator != null) return
        lateinit var worker: AppProfileCoordinator
        worker = AppProfileCoordinator(this, container.repository, container.profileStorage, container.settingsStorage) { error ->
            handler.post {
                if (!destroyed && coordinator === worker) {
                    worker.stop()
                    coordinator = null
                    disconnected(error)
                }
            }
        }
        coordinator = worker
        worker.start()
    }

    private fun connect() {
        if (destroyed || connecting) return
        if (!connectionAttemptLogged) {
            connectionAttemptLogged = true
            Log.i(TAG, "Connecting app detection")
            recordEvent(HistoryEventType.SERVICE, "Connecting app detection", "Connecting to the privileged service.")
        }
        connecting = true
        handler.removeCallbacks(reconnect)
        val current = ++generation
        val previous = subscription
        subscription = null
        lastObservation = null
        VisibleAppWindowEvents.clear()
        AppDetectionState.update(false, AppDetectionState.status.value.error)
        startCoordinator()
        // Cleanup must still run if Android destroys us before the coroutine starts.
        if (previous != null) Thread { runCatching { previous.close() } }.start()
        handler.removeCallbacks(firstObservationTimeout)
        handler.postDelayed(firstObservationTimeout, 10_000L)
        scope.launch {
            val result = runCatching {
                // The settings observer and first service start can race. Resolve the persisted
                // choice before registering, rather than briefly launching the wrong host.
                val settings = container.settingsStorage.settings.first()
                container.privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
                container.hostClient.observeAppWindows(
                    onObservation = { observation -> handler.post {
                        if (!destroyed && generation == current) accept(observation)
                    } },
                    onDisconnected = { handler.post {
                        if (!destroyed && generation == current) disconnected(IllegalStateException("Privileged service disconnected"))
                    } },
                ).getOrThrow()
            }
            handler.post {
                if (destroyed || generation != current) {
                    // No host start during cleanup, including when Android destroys us mid-connect.
                    result.getOrNull()?.let { stale -> Thread { stale.close() }.start() }
                } else {
                    connecting = false
                    result.fold(onSuccess = { subscription = it }, onFailure = ::disconnected)
                }
            }
        }
    }

    private fun accept(observation: HostAppObservation) {
        if (observation.error != null) {
            failed(IllegalStateException(observation.error))
            return
        }
        failures = 0
        handler.removeCallbacks(firstObservationTimeout)
        handler.removeCallbacks(reconnect)
        lastObservation = observation
        if (!AppDetectionState.status.value.available) {
            Log.i(TAG, "App detection connected")
            container.historyLogger.recovered("app-detection")
            recordEvent(HistoryEventType.SERVICE, "App detection connected", "App profiles can follow visible apps.")
        }
        connectionAttemptLogged = false
        AppDetectionState.update(true)
        publish()
    }

    private fun publish() {
        try {
            val power = getSystemService(PowerManager::class.java)
            if (power?.isInteractive != true) lastObservation = null
            val activeDisplays = displays?.displays.orEmpty().filter { it.state == Display.STATE_ON }
            val refreshRates = activeDisplays.mapNotNull { display ->
                nominalDisplayRefreshRateFps(display.refreshRate)?.let { display.displayId to it }
            }.toMap()
            val inputMethod = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            VisibleAppWindowEvents.publish(privilegedAppSnapshot(
                tasks = lastObservation?.windows.orEmpty(),
                interactive = power?.isInteractive == true,
                refreshRates = refreshRates,
                excludedPackages = TRANSIENT_APP_WINDOW_PACKAGES + VENDOR_GAME_ASSISTANT_PACKAGES +
                    setOfNotNull(packageName, inputMethod),
                activeDisplayIds = activeDisplays.mapTo(mutableSetOf()) { it.displayId },
            ).copy(hasAppObservation = lastObservation != null))
        } catch (error: RuntimeException) {
            failed(error)
        }
    }

    private fun refresh() {
        if (destroyed) return
        // Screen/display changes immediately invalidate sleeping or removed displays. The fresh
        // task query also runs after wake, without waiting for a task-change notification.
        publish()
        val current = generation
        val active = subscription
        if (active == null) {
            connect()
        } else scope.launch {
            active.refresh().onFailure { error -> handler.post {
                if (!destroyed && generation == current) disconnected(error)
            } }
        }
    }

    private fun disconnected(error: Throwable) {
        // A callback already queued on the main thread can arrive after Binder death. Retire
        // its generation now, so stale success cannot cancel the pending reconnection.
        generation++
        connecting = false
        val previous = subscription
        subscription = null
        if (previous != null) Thread { runCatching { previous.close() } }.start()
        failed(error)
    }

    private fun failed(error: Throwable) {
        handler.removeCallbacks(firstObservationTimeout)
        scope.launch {
            container.historyLogger.failure("app-detection", "App detection unavailable", error,
                "App profiles are paused; reconnection is automatic")
        }
        lastObservation = null
        VisibleAppWindowEvents.clear()
        AppDetectionState.update(false, error.message ?: "App detection unavailable")
        if (!destroyed && !handler.hasCallbacks(reconnect)) {
            handler.postDelayed(reconnect, (500L shl failures.coerceAtMost(6)).coerceAtMost(30_000L))
            failures++
        }
    }

    private fun recordEvent(type: HistoryEventType, title: String, detail: String) {
        val entry = ProfileSwitchHistoryEntry(System.currentTimeMillis(), null, title, detail, type, false)
        scope.launch { container.historyLogger.append(entry) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        generation++
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(sourceChanged)
        displays?.unregisterDisplayListener(displayListener)
        val previous = subscription
        subscription = null
        Thread { runCatching { previous?.close() } }.start()
        coordinator?.stop()
        coordinator = null
        VisibleAppWindowEvents.clear()
        AppDetectionState.update(false)
        Log.i(TAG, "App detection stopped")
        scope.cancel()
        container.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppDetection"
        private const val EXTRA_RETRY = "retry"
        fun start(context: Context, retry: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, AppDetectionService::class.java)
                .putExtra(EXTRA_RETRY, retry))
        }
    }
}
