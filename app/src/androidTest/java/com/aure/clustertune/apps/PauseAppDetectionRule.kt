package com.aure.clustertune.apps

import android.app.ActivityManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** Direct host tests select their own execution method; pause the normal app's host owner. */
internal class PauseAppDetectionRule : ExternalResource() {
    private var wasRunning = false

    @Suppress("DEPRECATION")
    private fun running(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .any { it.service.className == AppDetectionService::class.java.name }
    }

    override fun before() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf("input keyevent KEYCODE_WAKEUP", "wm dismiss-keyguard").forEach { command ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        wasRunning = running()
        context.stopService(Intent(context, AppDetectionService::class.java))
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000L
        while (running() && android.os.SystemClock.uptimeMillis() < deadline) Thread.sleep(50)
        check(!running()) { "background automation must stop before selecting a test host" }
    }

    override fun after() {
        if (wasRunning) AppDetectionService.start(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
