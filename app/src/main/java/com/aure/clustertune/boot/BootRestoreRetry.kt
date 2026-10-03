package com.aure.clustertune.boot

internal suspend fun <T> retryBootRestore(
    attemptDelaysMs: List<Long>,
    wait: suspend (Long) -> Unit,
    apply: suspend (attempt: Int) -> Result<T>,
): Result<T> {
    require(attemptDelaysMs.isNotEmpty()) { "At least one boot restore attempt is required" }
    require(attemptDelaysMs.all { it >= 0L }) { "Boot restore delays cannot be negative" }

    var lastResult: Result<T>? = null
    attemptDelaysMs.forEachIndexed { index, delayMs ->
        if (delayMs > 0L) wait(delayMs)
        val result = apply(index + 1)
        if (result.isSuccess) return result
        lastResult = result
    }
    return checkNotNull(lastResult)
}
