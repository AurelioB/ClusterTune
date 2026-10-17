package com.aure.clustertune.apps

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** Thor's game control panel, including its secondary-screen refresh-rate button. */
internal class LetterRefreshModeDriver(private val ui: UiAutomation) : AutoCloseable {
    private val panelPackage = "com.odin.dualscreen.assistant"

    init {
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    fun selectHz(expected: Int): JSONObject {
        check(expected in listOf(60, 120))
        val before = physicalHz()
        val result = JSONObject().put("beforeHz", before).put("requestedHz", expected)
        if (kotlin.math.abs(before - expected) < 1) return result.put("alreadySelected", true)
        try {
            if (button() == null) togglePanel()
            val deadline = SystemClock.uptimeMillis() + 5_000
            var control = button()
            while (control == null && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
                control = button()
            }
            check(control != null) { "Game-panel refresh button missing: ${nodes().map { it.viewIdResourceName to it.text }}" }
            result.put("button", control.text).put("package", panelPackage)
            check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Game-panel refresh button rejected click" }
            // Let the OEM finish changing the mode before independently reading its physical state.
            SystemClock.sleep(1_000)
        } finally {
            close()
        }
        val actual = physicalHz()
        check(kotlin.math.abs(actual - expected) < 1) { "Game-panel refresh click did not select $expected Hz; actual=$actual" }
        return result.put("physicalHz", actual)
    }

    /** Bounded reads; an OEM dump-in-progress reply is not valid display information. */
    fun physicalHz(): Double {
        var output = ""
        repeat(3) {
            output = shell("dumpsys -t 3 display")
            val line = output.lineSequence().firstOrNull { it.contains("DisplayDeviceInfo{\"Built-in Screen\"") }
            if (line != null) {
                val mode = Regex(", modeId (\\d+),").find(line)?.groupValues?.get(1)
                if (mode != null) {
                    Regex("\\{id=$mode, width=\\d+, height=\\d+, fps=([0-9.]+)")
                        .find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { return it }
                }
            }
            SystemClock.sleep(200)
        }
        error("Cannot read the physical display mode: ${output.take(500)}")
    }

    override fun close() {
        if (button() != null) togglePanel()
    }

    private fun button() = nodes().firstOrNull {
        it.packageName?.toString() == panelPackage && it.viewIdResourceName == "$panelPackage:id/frame_rate_mode"
    }

    private fun togglePanel() {
        // Reproduce the OEM hardware menu button's release event. Root is used only to open
        // or close this protected system menu; the refresh change uses its actual UI button.
        shell("su -c sh", "am broadcast -a action.tcc.button.key.event -p $panelPackage --ez key_action_down false --ez key_isLongPress false\n")
        SystemClock.sleep(500)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) out += node
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        val windows = ui.windowsOnAllDisplays
        repeat(windows.size()) { index -> windows.valueAt(index).forEach { visit(it.root) } }
        return out
    }

    private fun shell(command: String, input: String = ""): String {
        val streams = ui.executeShellCommandRwe(command)
        ParcelFileDescriptor.AutoCloseOutputStream(streams[1]).use { it.write(input.toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use { it.readText() } +
            ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use { it.readText() }
    }
}
