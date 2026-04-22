package com.aure.androidtuner.root

import android.content.Context
import android.util.Log
import java.io.File

object RootSupport {
    private const val TAG = "RootSupport"

    fun hasPServer(): Boolean {
        return RootExec().pServerAvailable
    }

    fun startActivityRoot(context: Context, activity: String): String? {
        return runRootCommand(context, "am start -n $activity")
    }

    fun runRootCommand(context: Context, command: String): String? {
        Log.d(TAG, "running root command= $command")
        val rootExec = RootExec()
        val result = rootExec.executeAsRoot(command)
        Log.d(TAG, "command finished with result: $result")
        return result.getOrNull()
    }

    fun runRootScript(
        context: Context,
        script: String,
        subfolder: String = ".",
        logFileName: String = "root-script.log",
    ): String? {
        val filesPath = File(context.filesDir, subfolder).absolutePath
        val logPath = File(context.filesDir, logFileName).absolutePath
        val command = "sh $filesPath/support/subscripts/$script $filesPath > $logPath"
        Log.d(TAG, "running root script with cmd= $command")
        val rootExec = RootExec()
        val result = rootExec.executeAsRoot(command)
        Log.d(TAG, "$script finished with result: $result")
        return result.getOrNull()
    }

    fun runGeneratedScript(
        context: Context,
        scriptName: String,
        scriptContents: String,
        logFileName: String = scriptName.substringBeforeLast('.', scriptName) + ".log",
    ): String? {
        val scriptDir = File(context.filesDir, "root-scripts")
        if (!scriptDir.exists()) {
            scriptDir.mkdirs()
        }

        val scriptFile = File(scriptDir, scriptName)
        scriptFile.writeText(scriptContents)
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)

        val command = "sh ${scriptFile.absolutePath}"
        Log.d(TAG, "running generated root script with cmd= $command")

        val rootExec = RootExec()
        val result = rootExec.executeAsRoot(command)
        Log.d(TAG, "$scriptName finished with result: $result")

        return result.getOrNull()
    }
}
