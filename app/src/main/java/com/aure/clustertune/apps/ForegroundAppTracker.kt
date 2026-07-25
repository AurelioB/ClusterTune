package com.aure.clustertune.apps

internal class ForegroundAppTracker {
    private var foregroundActivity: ActivityIdentity? = null

    val foregroundPackage: String?
        get() = foregroundActivity?.packageName

    fun onActivityResumed(
        packageName: String?,
        className: String?,
    ) {
        if (packageName.isNullOrBlank()) return
        foregroundActivity = ActivityIdentity(
            packageName = packageName,
            className = className,
        )
    }

    fun onActivityPaused(
        packageName: String?,
        className: String?,
    ) {
        val current = foregroundActivity ?: return
        if (
            current.matches(
                packageName = packageName,
                className = className,
            )
        ) {
            foregroundActivity = null
        }
    }

    fun onActivityStopped() {
        // onPause already describes the foreground transition. A delayed onStop may belong
        // to an older activity after another activity in the same package has resumed.
    }

    private data class ActivityIdentity(
        val packageName: String,
        val className: String?,
    ) {
        fun matches(
            packageName: String?,
            className: String?,
        ): Boolean {
            if (this.packageName != packageName) return false
            if (this.className != null && className != null) {
                return this.className == className
            }
            return true
        }
    }
}
