package com.aure.clustertune.root.host

import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.root.ExecutionProbeResult
import com.aure.clustertune.root.HostLaunchRequest
import com.aure.clustertune.root.PrivilegedExecutionMethod
import com.aure.clustertune.root.PrivilegedExecutionResolver

/** Emulator adb root launches the unmodified host when no app-facing root manager exists. */
internal fun privilegedHostTestResolver(): PrivilegedExecutionResolver {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val normal = PrivilegedExecutionResolver.default(context)
    if (normal.autoDetectBestMethod() != null || !android.os.Build.MODEL.startsWith("sdk_gphone")) return normal
    val method = object : PrivilegedExecutionMethod {
        override val id = "root-shell"
        override fun probe() = ExecutionProbeResult(hostTestShell("su 0 id -u").trim() == "0")
        override fun launchHost(request: HostLaunchRequest): Result<Unit> = runCatching {
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            val launcher = java.io.File(context.cacheDir, "emulator-root-host.sh")
            launcher.writeText("cd ${quote(request.workingDirectory)} && sh ${quote(request.launcherScript)}\n")
            try { hostTestShell("su 0 sh ${launcher.absolutePath}") } finally { launcher.delete() }
        }
    }
    return PrivilegedExecutionResolver(listOf(method), listOf(method.id))
}

internal fun hostTestShell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
).bufferedReader().use { it.readText() }
