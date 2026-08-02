package com.aure.clustertune.permissions

/** App-level access that may be needed by ClusterTune features. */
enum class AppAccess {
    OVERLAY,
    USAGE,
    NOTIFICATIONS,
}

/** Snapshot of the app access grants used to determine which features are available. */
data class AppAccessStatus(
    val overlayGranted: Boolean,
    val usageGranted: Boolean,
    val notificationsGranted: Boolean,
)

/** Returns missing access in the stable order used by the permission check UI. */
fun missingAppAccess(status: AppAccessStatus): List<AppAccess> = buildList {
    if (!status.overlayGranted) add(AppAccess.OVERLAY)
    if (!status.usageGranted) add(AppAccess.USAGE)
    if (!status.notificationsGranted) add(AppAccess.NOTIFICATIONS)
}
