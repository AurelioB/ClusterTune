package com.aure.clustertune.root.host

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.os.IBinder

/** Keeps the existing seven-day foreground-activity semantics, including out-of-order events. */
internal class RecentAppHistory(
    private val beginTimeMs: Long,
    private val endTimeMs: Long,
    private val limit: Int = HostProtocol.MAX_RECENT_APPS,
) {
    private val lastActive = mutableMapOf<String, Long>()

    init {
        require(beginTimeMs >= 0 && endTimeMs >= beginTimeMs)
        require(limit in 0..HostProtocol.MAX_RECENT_APPS)
    }

    fun record(packageName: String?, timestampMs: Long, activityResumed: Boolean) {
        if (!activityResumed || timestampMs < beginTimeMs || timestampMs >= endTimeMs ||
            packageName == null || !HostTelemetryParsers.isValidPackageName(packageName)
        ) return
        lastActive[packageName] = maxOf(lastActive[packageName] ?: Long.MIN_VALUE, timestampMs)
    }

    fun packages(): List<String> = lastActive.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { it.key }
}

/** Only called in the root/system host with its own Binder identity; never grants app access. */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi")
internal object HostRecentApps {
    private const val LOOKBACK_MS = 7L * 24L * 60L * 60L * 1_000L

    @JvmStatic
    fun read(ownerUid: Int): List<String> {
        val now = System.currentTimeMillis()
        val begin = (now - LOOKBACK_MS).coerceAtLeast(0L)
        val type = Class.forName("android.app.usage.IUsageStatsManager")
        val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
            .invoke(null, "usagestats") as? IBinder ?: error("App history service is unavailable")
        val manager = Class.forName("${type.name}\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
        val events = type.getMethod("queryEventsForUser", Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            .invoke(manager, begin, now, ownerUid / 100_000, "android") as? UsageEvents
            ?: error("Recent app history is unavailable")
        val history = RecentAppHistory(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (events.getNextEvent(event)) {
                history.record(event.packageName, event.timeStamp, event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            }
        }
        return history.packages()
    }
}
