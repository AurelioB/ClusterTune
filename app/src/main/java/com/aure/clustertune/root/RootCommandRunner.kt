package com.aure.clustertune.root

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandRunner(
    context: Context,
    private val executionResolver: PrivilegedExecutionResolver = PrivilegedExecutionResolver.default(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private companion object {
        const val TAG = "RootCommandRunner"
    }

    val isAvailable: Boolean
        get() = executionResolver.isAvailable

    val selectedExecutionMethodId: String?
        get() = executionResolver.selectedMethodId

    suspend fun executeScript(script: String): Result<String?> = withContext(dispatcher) {
        executionResolver.executeScript(
            scriptName = "apply-frequencies.sh",
            scriptContents = script,
            captureResult = true,
        ).flatMapCatching { output ->
            // 1.0.2 began requiring the script to echo a completion marker on
            // stdout. Fire-and-forget executors (the no-root JDWP injection) can
            // never satisfy that: the injected Runtime.exec() runs in another
            // process and its stdout never returns here, so output is always
            // null and EVERY apply was reported as failed even though the script
            // ran and the caps were written. Only enforce the marker for methods
            // that actually report stdout; for the others the caller's sysfs
            // read-back verification is the real check.
            if (!executionResolver.selectedMethodSupportsStdout) {
                Result.success(output)
            } else if (output.orEmpty().lineSequence().any { it.trim() == PerformanceCommandBuilder.COMPLETION_MARKER }) {
                Result.success(output)
            } else {
                Result.failure(IllegalStateException("Privileged script did not report completion"))
            }
        }.onFailure { error ->
            // android.util.Log is not implemented by local JVM tests. Keep diagnostics from
            // changing the execution result if the platform logger itself is unavailable.
            runCatching {
                Log.w(
                    TAG,
                    "Privileged script failed via ${executionResolver.selectedMethodId ?: "unknown"}: ${error.message}",
                    error,
                )
            }
        }
    }
}

private fun <T, R> Result<T>.flatMapCatching(transform: (T) -> Result<R>): Result<R> =
    fold(
        onSuccess = { value -> runCatching { transform(value).getOrThrow() } },
        onFailure = { error -> Result.failure(error) },
    )
