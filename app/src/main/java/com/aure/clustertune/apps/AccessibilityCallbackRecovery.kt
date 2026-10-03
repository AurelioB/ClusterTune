package com.aure.clustertune.apps

/** Main-thread callback boundary. Healthy operation is event-driven; only failures schedule retries. */
internal class AccessibilityCallbackRecovery(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onFailure: (RuntimeException) -> Unit,
    private val action: () -> Unit,
) {
    private var enabled = false
    private var failures = 0
    private val retry = Runnable { run() }

    fun start() {
        cancel(retry)
        failures = 0
        enabled = true
    }

    fun stop() {
        enabled = false
        cancel(retry)
    }

    fun run() {
        if (!enabled) return
        cancel(retry)
        try {
            action()
            failures = 0
        } catch (error: RuntimeException) {
            failed(error)
        }
    }

    fun failed(error: RuntimeException) {
        if (!enabled) return
        cancel(retry)
        onFailure(error)
        failures++
        if (failures <= 3) schedule(retry, 250L shl (failures - 1))
    }
}
