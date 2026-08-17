package com.wuyr.jdwp_injector.debug

import android.util.Log

/**
 * A tiny in-memory log the wireless-debug setup UI can display live, so
 * pairing/connection issues can be diagnosed on-device without adb or
 * rebuilding. Also mirrors to Logcat.
 *
 * Compose-free (this is a library module). The app observes changes via
 * [setListener] and reads [snapshot].
 */
object JdwpDebugLog {
    private const val TAG = "ClusterTuneJdwpConn"
    // Sized so that a full apply — including the injected script's own trace and
    // the before/after sysfs listings — fits alongside the connection history.
    // The users who can reproduce the policy0 failure have no PC, so the in-app
    // log is the ONLY channel; losing the head of it to a 200-line cap means
    // losing the evidence.
    private const val MAX = 800

    private val buffer = ArrayDeque<String>()
    private var listener: (() -> Unit)? = null

    /** Register a callback invoked whenever a line is added/cleared (on any thread). */
    fun setListener(l: (() -> Unit)?) { listener = l }

    /** Current lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun d(message: String) {
        runCatching { Log.d(TAG, message) }
        add(message)
    }

    fun w(message: String, t: Throwable? = null) {
        runCatching { Log.w(TAG, message, t) }
        add("! " + message + (t?.message?.let { ": $it" } ?: ""))
    }

    private fun add(message: String) {
        // DateFormat.format is an Android API; under plain JVM unit tests there is
        // no Android runtime, so it (and Log above) would throw "not mocked".
        // Guard both so diagnostic logging never breaks a caller — most
        // importantly so the release pipeline's unit tests, which exercise code
        // paths that log, don't fail on an Android stub.
        val ts = runCatching {
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        }.getOrElse { "" }
        synchronized(buffer) {
            buffer.addLast(if (ts.isEmpty()) message else "[$ts] $message")
            while (buffer.size > MAX) buffer.removeFirst()
        }
        listener?.invoke()
    }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        listener?.invoke()
    }
}
