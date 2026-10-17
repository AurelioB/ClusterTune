package com.aure.clustertune.root.host

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One session deadline/cleanup task. With no work, there is no executor or timer thread. */
class HostDeadlineTask @JvmOverloads constructor(
    private val monitor: Any,
    private val action: Runnable,
    private val clock: HostMonotonicClock = SystemHostMonotonicClock,
) : AutoCloseable {
    private var executor: ScheduledThreadPoolExecutor? = null
    private var task: ScheduledFuture<*>? = null
    private var scheduledDeadline: Long? = null
    private var generation = 0L
    private var closed = false

    val isScheduled: Boolean get() = synchronized(monitor) { task != null }

    fun scheduleAt(deadlineNanos: Long?) = synchronized(monitor) {
        if (closed) return@synchronized
        if (deadlineNanos == null) {
            cancelAndRelease()
            return@synchronized
        }
        // Renewing a heartbeat can leave the earlier timeout in place. At that deadline we
        // recheck and schedule the renewed deadline, avoiding timer churn on every sample.
        if (task != null && scheduledDeadline?.let { it <= deadlineNanos } == true) return@synchronized
        task?.cancel(false)
        val token = ++generation
        val scheduler = executor ?: ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "ClusterTune-session-deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }.also { executor = it }
        scheduledDeadline = deadlineNanos
        task = scheduler.schedule({
            synchronized(monitor) callback@{
                if (closed || token != generation) return@callback
                task = null
                scheduledDeadline = null
                try {
                    action.run()
                } finally {
                    if (task == null) cancelAndRelease()
                }
            }
        }, (deadlineNanos - clock.nanoTime()).coerceAtLeast(0L), TimeUnit.NANOSECONDS)
    }

    override fun close() = synchronized(monitor) {
        closed = true
        cancelAndRelease()
    }

    private fun cancelAndRelease() {
        generation++
        task?.cancel(false)
        task = null
        scheduledDeadline = null
        executor?.shutdown()
        executor = null
    }
}
