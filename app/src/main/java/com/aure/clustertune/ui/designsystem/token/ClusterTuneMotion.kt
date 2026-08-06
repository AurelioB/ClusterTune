package com.aure.clustertune.ui.designsystem.token

/** Motion durations in milliseconds. Callers should skip animation when reduced motion is enabled. */
internal object ClusterTuneMotion {
    const val stateChangeMillis: Int = 150
    const val modalVisibilityMillis: Int = 300
    const val reducedMotionMillis: Int = 0

    fun durationMillis(reducedMotion: Boolean, normal: Int = stateChangeMillis): Int =
        if (reducedMotion) reducedMotionMillis else normal
}
