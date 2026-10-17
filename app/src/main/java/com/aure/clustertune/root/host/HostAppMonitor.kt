package com.aure.clustertune.root.host

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.Proxy

data class HostAppWindow(
    val packageName: String,
    val displayId: Int,
    val focused: Boolean,
    val lastActiveTime: Long,
    val basePackageName: String? = null,
)

/** An error explicitly invalidates the previous observation, rather than retaining stale apps. */
data class HostAppObservation(val windows: List<HostAppWindow>, val error: String? = null)

interface HostAppSubscription : AutoCloseable {
    fun refresh(): Result<Unit>
}

internal object HostAppObservationWire {
    fun write(parcel: Parcel, observation: HostAppObservation) {
        require(observation.windows.size <= HostProtocol.MAX_APP_WINDOWS)
        parcel.writeString(observation.error?.take(HostProtocol.MAX_METADATA_LENGTH))
        parcel.writeInt(observation.windows.size)
        observation.windows.forEach {
            parcel.writeString(it.packageName)
            parcel.writeInt(it.displayId)
            parcel.writeInt(if (it.focused) 1 else 0)
            parcel.writeLong(it.lastActiveTime)
            parcel.writeString(it.basePackageName)
        }
    }

    fun read(parcel: Parcel): HostAppObservation {
        val error = parcel.readString()
        require(error == null || error.length <= HostProtocol.MAX_METADATA_LENGTH)
        val count = parcel.readInt()
        require(count in 0..HostProtocol.MAX_APP_WINDOWS) { "invalid app window count" }
        return HostAppObservation(List(count) {
            val pkg = requireNotNull(parcel.readString())
            require(HostTelemetryParsers.isValidPackageName(pkg)) { "invalid app package" }
            val displayId = parcel.readInt()
            require(displayId >= 0) { "invalid app display" }
            val focused = parcel.readInt()
            require(focused in 0..1) { "invalid app focus" }
            val lastActiveTime = parcel.readLong()
            val basePackage = parcel.readString()
            require(basePackage == null || HostTelemetryParsers.isValidPackageName(basePackage)) { "invalid base package" }
            HostAppWindow(pkg, displayId, focused == 1, lastActiveTime, basePackage)
        }, error)
    }
}

/**
 * All framework queries run on our own thread, with the host's identity. Task notifications
 * trigger a coalesced query and two settling samples, never a periodic foreground-app poll.
 * The hidden interface is resolved from this device, including its transaction numbers.
 */
internal interface HostTaskSource : AutoCloseable {
    fun start()
    fun read(ownerUser: Int): List<HostAppWindow>
}

internal class HostAppMonitor @JvmOverloads constructor(
    private val ownerUid: Int,
    private val createSource: (() -> Unit) -> HostTaskSource = ::FrameworkTaskSource,
) : AutoCloseable {
    private val thread = HandlerThread("ClusterTune-app-detection").apply { start() }
    private val handler = Handler(thread.looper)
    private val callbacks = linkedMapOf<IBinder, IBinder.DeathRecipient>()
    private var source: HostTaskSource? = null
    private var closed = false
    private var failures = 0
    private val refresh = Runnable { sample() }
    private val settled = Runnable { sample() }
    private val settledAgain = Runnable { sample() }

    @Synchronized
    fun subscribe(callback: IBinder) {
        check(!closed) { "app monitor closed" }
        if (callback in callbacks) {
            changed()
            return
        }
        // Registration must fail visibly when a firmware denies this capability.
        if (source == null) source = createSource(::changed).also {
            try { it.start() } catch (error: Exception) {
                runCatching { it.close() }
                throw error
            }
        }
        val death = IBinder.DeathRecipient { unsubscribe(callback) }
        try {
            callback.linkToDeath(death, 0)
            callbacks[callback] = death
        } catch (error: Exception) {
            if (callbacks.isEmpty()) stopSource()
            throw error
        }
        changed()
    }

    @Synchronized
    fun unsubscribe(callback: IBinder) {
        callbacks.remove(callback)?.let { runCatching { callback.unlinkToDeath(it, 0) } }
        if (callbacks.isEmpty()) stopSource()
    }

    @Synchronized
    private fun changed() {
        if (closed) return
        if (!handler.hasCallbacks(refresh)) handler.postDelayed(refresh, 50L)
        handler.removeCallbacks(settled)
        handler.removeCallbacks(settledAgain)
        handler.postDelayed(settled, 250L)
        handler.postDelayed(settledAgain, 750L)
    }

    private fun sample() {
        val observedSource = synchronized(this) {
            if (closed || callbacks.isEmpty()) return
            requireNotNull(source)
        }
        // A slow framework query must not hold the monitor lock: subscriptions and host
        // shutdown still need to proceed, including while Android is unresponsive.
        val observation = try {
            HostAppObservation(observedSource.read(ownerUid / 100_000))
        } catch (error: Exception) {
            HostAppObservation(emptyList(), error.cause?.message ?: error.message ?: "App detection failed")
        }
        val targets = synchronized(this) {
            if (closed || source !== observedSource) return
            if (observation.error == null) {
                failures = 0
            } else if (++failures <= 3 && !handler.hasCallbacks(refresh)) {
                // Only failures schedule retries without a new task event.
                handler.postDelayed(refresh, 250L shl (failures - 1))
            }
            callbacks.keys.toList()
        }
        targets.forEach { callback ->
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(HostProtocol.APP_WINDOWS_DESCRIPTOR)
                data.writeInt(HostProtocol.VERSION)
                HostAppObservationWire.write(data, observation)
                if (!callback.transact(HostProtocol.APP_WINDOWS_CHANGED, data, null, IBinder.FLAG_ONEWAY)) {
                    unsubscribe(callback)
                }
            } catch (_: Exception) {
                unsubscribe(callback)
            } finally {
                data.recycle()
            }
        }
    }

    private fun stopSource() {
        handler.removeCallbacksAndMessages(null)
        runCatching { source?.close() }
        source = null
        failures = 0
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        callbacks.forEach { (callback, death) -> runCatching { callback.unlinkToDeath(death, 0) } }
        callbacks.clear()
        stopSource()
        thread.quitSafely()
    }
}

/** Keep framework reflection isolated from the app-side detection and profile rules. */
// This adapter runs exclusively in the root/system app_process host, never the app sandbox.
@SuppressLint("PrivateApi", "BlockedPrivateApi")
private class FrameworkTaskSource(private val changed: () -> Unit) : HostTaskSource {
    private val descriptor = "android.app.ITaskStackListener"
    private val listenerType = Class.forName(descriptor)
    private val managerType = Class.forName("android.app.IActivityTaskManager")
    private val manager = Class.forName("android.app.ActivityTaskManager")
        .getDeclaredMethod("getService").invoke(null)
    private val taskFields = Class.forName("android.app.TaskInfo")
    private val visible = taskFields.getField("isVisible")
    private val focused = taskFields.getField("isFocused")
    private val display = taskFields.getField("displayId")
    private val activeTime = taskFields.getField("lastActiveTime")
    private val user = taskFields.getField("userId")
    private val getTasks = managerType.methods.firstOrNull {
        it.name == "getTasks" && it.parameterTypes.contentEquals(arrayOf(
            Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ))
    } ?: managerType.getMethod("getTasks", Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    private val transactions = Class.forName("$descriptor\$Stub").declaredFields
        .filter { it.name.startsWith("TRANSACTION_") }
        .associate { field ->
            field.isAccessible = true
            field.getInt(null) to field.name.removePrefix("TRANSACTION_")
        }
    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(descriptor)
                return true
            }
            val name = transactions[code] ?: return super.onTransact(code, data, reply, flags)
            data.enforceInterface(descriptor)
            // Snapshot images are irrelevant to app identity; don't deserialize or retain them.
            if (name != "onTaskSnapshotChanged") changed()
            return true
        }
    }
    private val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
        when (method.name) {
            "asBinder" -> callbackBinder
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "ClusterTune task listener"
            else -> null
        }
    }

    override fun start() {
        managerType.getMethod("registerTaskStackListener", listenerType).invoke(manager, listener)
    }

    override fun read(ownerUser: Int): List<HostAppWindow> {
        val args: Array<Any> = if (getTasks.parameterCount == 4) arrayOf(HostProtocol.MAX_APP_WINDOWS, false, false, -1)
            else arrayOf(HostProtocol.MAX_APP_WINDOWS, false, false)
        val tasks = getTasks.invoke(manager, *args) as List<*>
        // Several displays can each report a focused task. The globally focused root tells
        // Auto Tune which display the user is interacting with, independently of resume times.
        val focusedRoot = managerType.getMethod("getFocusedRootTaskInfo").invoke(manager)
        val focusedDisplay = focusedRoot?.let { display.getInt(it) }
        return tasks.mapNotNull { item ->
            val task = item as ActivityManager.RunningTaskInfo
            if (!visible.getBoolean(task) || user.getInt(task) != ownerUser) return@mapNotNull null
            val pkg = task.topActivity?.packageName ?: return@mapNotNull null
            val displayId = display.getInt(task)
            if (displayId < 0 || !HostTelemetryParsers.isValidPackageName(pkg)) return@mapNotNull null
            HostAppWindow(pkg, displayId, focused.getBoolean(task) && displayId == focusedDisplay,
                activeTime.getLong(task), task.baseActivity?.packageName)
        }.distinct()
    }

    override fun close() {
        managerType.getMethod("unregisterTaskStackListener", listenerType).invoke(manager, listener)
    }
}
