package com.aure.clustertune.apps

internal class ForegroundAppTracker {
    private var foregroundActivity: ActivityIdentity? = null
    private var lastResumeTimestamp: Long = Long.MIN_VALUE

    val foregroundPackage: String?
        get() = foregroundActivity?.packageName

    fun onActivityResumed(
        packageName: String?,
        className: String?,
        eventTimestamp: Long? = null,
    ) {
        if (packageName.isNullOrBlank()) return
        foregroundActivity = ActivityIdentity(
            packageName = packageName,
            className = className,
        )
        if (eventTimestamp != null) lastResumeTimestamp = eventTimestamp
    }

    fun onActivityPaused(
        packageName: String?,
        className: String?,
        eventTimestamp: Long? = null,
    ) {
        if (shouldClear(packageName, className, eventTimestamp)) foregroundActivity = null
    }

    fun onActivityStopped(
        packageName: String? = null,
        className: String? = null,
        eventTimestamp: Long? = null,
    ) {
        if (shouldClear(packageName, className, eventTimestamp)) foregroundActivity = null
    }

    private fun shouldClear(
        packageName: String?,
        className: String?,
        eventTimestamp: Long?,
    ): Boolean {
        val current = foregroundActivity ?: return false
        if (!current.matches(packageName, className)) return false

        if (eventTimestamp != null && eventTimestamp < lastResumeTimestamp) return false

        // UsageStats may deliver an old package-level background event after a
        // newer activity was resumed (notably when an emulator switches games).
        // Event timestamps let us discard that stale event instead of dropping
        // the still-current package. Without a timestamp, an ambiguous event
        // is ignored rather than risking a false background transition.
        if (className == null) {
            return eventTimestamp != null
        }
        return true
    }

    private data class ActivityIdentity(
        val packageName: String,
        val className: String?,
    ) {
        fun matches(packageName: String?, className: String?): Boolean =
            this.packageName == packageName && (this.className == null || className == null || this.className == className)
    }
}
