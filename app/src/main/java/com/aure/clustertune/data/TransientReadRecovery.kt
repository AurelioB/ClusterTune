package com.aure.clustertune.data

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen

/** Retry transient storage reads; corrupt data and programming errors must remain visible. */
internal fun <T> Flow<T>.retryTransientReads(onRetry: suspend (IOException) -> Unit): Flow<T> =
    retryWhen { cause, attempt ->
        if (cause !is IOException || attempt >= 3) return@retryWhen false
        onRetry(cause)
        delay(250L shl attempt.toInt())
        true
    }
