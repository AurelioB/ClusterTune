package com.aure.clustertune.root.host

/** Exact verification during one fixed-profile request; no writes, scheduler, or idle work. */
internal fun verifySettledHostMaximum(
    fs: HostFilesystem,
    id: String,
    path: String,
    targets: List<Long>,
    mode: Int,
    minimumPath: String?,
    nowNanos: () -> Long = System::nanoTime,
    waitMillis: (Long) -> Unit = Thread::sleep,
): Long {
    val started = nowNanos()
    var actual = fs.read(path)?.toLongOrNull()
    var minimum: Long? = null
    if (minimumPath != null) {
        // A launch boost can temporarily raise the effective policy minimum and maximum
        // above our accepted maximum request. Wait for it to finish before declaring that
        // the write failed and rolling back to the previous app's profile.
        while (actual !in targets && fs.mode(path) == mode) {
            minimum = fs.read(minimumPath)?.toLongOrNull()
            val elapsedMillis = (nowNanos() - started) / 1_000_000
            val budget = if (minimum != null && minimum > targets.max()) 2_000L else 40L
            if (elapsedMillis >= budget) break
            waitMillis(10L)
            actual = fs.read(path)?.toLongOrNull()
        }
    }
    check(actual != null && actual in targets) {
        "verification failed for $id: expected=${targets.joinToString("/")} actual=$actual minimum=$minimum"
    }
    val actualMode = fs.mode(path)
    check(actualMode == mode) { "permission verification failed for $id: expected=$mode actual=$actualMode" }
    return actual
}
