package com.aure.clustertune.root

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandRunner(
    context: Context,
    private val executionResolver: PrivilegedExecutionResolver = PrivilegedExecutionResolver.default(context),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    val isAvailable: Boolean
        get() = executionResolver.isAvailable

    val selectedExecutionMethodId: String?
        get() = executionResolver.selectedMethodId

    suspend fun executeScript(script: String): Result<String?> = withContext(dispatcher) {
        executionResolver.executeScript(
            scriptName = "apply-frequencies.sh",
            scriptContents = script,
            captureResult = false,
        )
    }
}
