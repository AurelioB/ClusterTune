package com.aure.clustertune.autotune

import kotlin.math.ceil
import kotlin.math.max

/** Budgets for displayed intervals, including the refresh slots needed for a fractional rate. */
object FramePacing {
    fun healthyP95Nanos(targetFps: Int, refreshPeriodNanos: Long?, multiplier: Double): Double =
        max(targetPeriodNanos(targetFps) * multiplier, longestRefreshInterval(targetFps, refreshPeriodNanos)) +
            toleranceNanos(refreshPeriodNanos)

    fun slowIntervalNanos(targetFps: Int, refreshPeriodNanos: Long?): Double =
        max(targetPeriodNanos(targetFps) * 1.5, longestRefreshInterval(targetFps, refreshPeriodNanos)) +
            toleranceNanos(refreshPeriodNanos)

    private fun targetPeriodNanos(targetFps: Int): Double =
        (1_000_000_000.0 / targetFps.coerceAtLeast(1)).coerceAtLeast(1.0)

    private fun longestRefreshInterval(targetFps: Int, refreshPeriodNanos: Long?): Double {
        val refresh = refreshPeriodNanos?.takeIf { it in 1_000_000L..1_000_000_000L } ?: return 0.0
        // A reported 16,666,666 ns refresh must still recognize a 30 FPS interval as two slots.
        val slots = ceil(targetPeriodNanos(targetFps) / refresh - 0.01).coerceAtLeast(1.0)
        return slots * refresh
    }

    private fun toleranceNanos(refreshPeriodNanos: Long?): Double =
        refreshPeriodNanos?.takeIf { it in 1_000_000L..1_000_000_000L }
            ?.let { max(500_000.0, it * 0.03) } ?: 0.0
}
