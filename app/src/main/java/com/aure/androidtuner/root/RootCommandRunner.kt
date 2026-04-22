package com.aure.androidtuner.root

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandRunner(
    private val context: Context,
    private val rootExec: RootExec = RootExec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    val isAvailable: Boolean
        get() = rootExec.pServerAvailable

    suspend fun executeScript(script: String): Result<String?> = withContext(dispatcher) {
        runCatching {
            RootSupport.runGeneratedScript(
                context = context,
                scriptName = "apply-frequencies.sh",
                scriptContents = script,
                logFileName = "apply-frequencies.log",
            )
        }
    }

    suspend fun runDiagnostics(): Result<String> = withContext(dispatcher) {
        val markerPath = "/data/local/tmp/android_tuner_root_test.txt"
        val script = """
            #!/system/bin/sh
            id
            getenforce
            echo android_tuner_root_ok > $markerPath
            cat $markerPath
        """.trimIndent()
        runCatching {
            val directOutput = RootSupport.runGeneratedScript(
                context = context,
                scriptName = "root-diagnostics.sh",
                scriptContents = script,
                logFileName = "root-diagnostics.log",
            ).orEmpty().trim()
            val markerOutput = RootSupport.runRootCommand(context, "cat $markerPath").orEmpty().trim()

            buildString {
                if (directOutput.isNotBlank()) {
                    appendLine("script output:")
                    appendLine(directOutput)
                }
                if (markerOutput.isNotBlank()) {
                    appendLine("marker output:")
                    append(markerOutput)
                }
            }.trim().ifBlank {
                "No output returned, but the test script may still have run"
            }
        }
    }
}
