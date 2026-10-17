package com.aure.clustertune.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.MainActivity
import com.aure.clustertune.AppContainer
import com.aure.clustertune.apps.AppDetectionService
import com.aure.clustertune.apps.AppProfileCoordinator
import com.aure.clustertune.apps.VisibleAppSnapshot
import com.aure.clustertune.apps.VisibleAppWindow
import com.aure.clustertune.apps.VisibleAppWindowEvents
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.EffectiveProfileSource
import com.aure.clustertune.root.host.ApplyRequest
import com.aure.clustertune.apps.AppDetectionState
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.logging.HistoryLogger
import com.aure.clustertune.logging.createHistoryShareIntent
import com.aure.clustertune.model.HistoryEventType
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit screenshot/chooser fixture: sample entries are temporary, never production defaults. */
@RunWith(AndroidJUnit4::class)
class HistoryEventsDeviceTest {
    @Test fun unknownWakeObservationKeepsTheCurrentAppProfile() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("historyRecovery") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(context)
        val profiles = container.profileStorage
        val settings = container.settingsStorage.settings.first()
        assumeTrue(!settings.autoTuneEnabled && !settings.sleepProfileEnabled)
        container.privilegedExecutionResolver.setConfiguredMethodId(settings.privilegedExecutionMethodId)
        val original = container.hostClient.readSnapshot().getOrThrow()
        val assignments = profiles.appProfileAssignments.first()
        val history = profiles.profileSwitchHistory.first()
        val effective = profiles.effectiveProfileState.first()
        context.stopService(Intent(context, AppDetectionService::class.java))
        repeat(100) { if (AppDetectionState.status.value.available) delay(50) }
        delay(200)
        val worker = AppProfileCoordinator(context, container.repository, profiles, container.settingsStorage)
        suspend fun await(condition: suspend () -> Boolean) {
            repeat(150) { if (condition()) return; delay(100) }
            check(condition()) { "Profile did not reach the expected state" }
        }
        try {
            val target = container.repository.observeState().first().displayProfiles.first { it.name == "Small Underclock" }
            profiles.saveAppProfileAssignment(AppProfileAssignment("com.aure.clustertune.fixture.a", "A", profileId = target.id))
            worker.start()
            VisibleAppWindowEvents.publish(VisibleAppSnapshot(
                windowsByDisplay = mapOf(0 to listOf(VisibleAppWindow("com.aure.clustertune.fixture.a", 0, true, true))),
                isInteractive = true,
            ))
            await { profiles.effectiveProfileState.first()?.source == EffectiveProfileSource.APP }
            val applied = profiles.effectiveProfileState.first()
            val clocks = container.hostClient.readSnapshot().getOrThrow().state.cpuMax
            VisibleAppWindowEvents.publish(VisibleAppSnapshot(isInteractive = true, hasAppObservation = false))
            delay(500)
            assertEquals(applied, profiles.effectiveProfileState.first())
            assertEquals(clocks, container.hostClient.readSnapshot().getOrThrow().state.cpuMax)
            VisibleAppWindowEvents.publish(VisibleAppSnapshot(isInteractive = true, hasAppObservation = true))
            await { profiles.effectiveProfileState.first()?.source == EffectiveProfileSource.NORMAL }
        } finally {
            worker.stop(); delay(200)
            container.hostClient.applyProfile(ApplyRequest(original.state.cpuMax, original.state.gpuMax, false,
                cpuIds = original.capabilities.cpus.map { it.id }, gpuId = original.capabilities.gpu?.id,
                gpuMaxPath = original.capabilities.gpu?.maxPath)).getOrThrow()
            profiles.deleteAppProfileAssignment("com.aure.clustertune.fixture.a")
            assignments.forEach { profiles.saveAppProfileAssignment(it) }
            if (effective != null) profiles.persistEffectiveProfileState(effective) else profiles.clearEffectiveProfileState()
            profiles.clearProfileSwitchHistory()
            history.asReversed().forEach { profiles.appendProfileSwitchHistory(it, maxOf(history.size, settings.profileSwitchHistoryLimit)) }
            AppDetectionService.start(context)
            container.close()
        }
    }

    @Test fun examplesAndRealShareSheet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("historyExamples") == "1")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val ui = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        fun nodes(): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser) result += node
                repeat(node.childCount) { visit(node.getChild(it)) }
            }
            val windows = ui.windowsOnAllDisplays
            repeat(windows.size()) { i -> windows.valueAt(i).forEach { visit(it.root) } }
            return result
        }
        fun click(text: String) {
            var node = nodes().first { it.packageName?.toString() == context.packageName && it.text?.toString() == text }
            while (!node.isClickable) node = requireNotNull(node.parent)
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        suspend fun await(condition: () -> Boolean) {
            repeat(150) { if (condition()) return; delay(100) }
            check(condition()) { "Timed out waiting for History UI" }
        }
        fun screenshot(name: String) {
            val bitmap = requireNotNull(ui.takeScreenshot())
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        shell("input -d 0 keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        val storage = ProfileStorage(context)
        val settings = SettingsStorage(context)
        val original = storage.profileSwitchHistory.first()
        val limit = settings.settings.first().profileSwitchHistoryLimit
        val effective = storage.effectiveProfileState.first()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            await { AppDetectionState.status.value.available }
            await { nodes().any { it.text?.toString() == "History" } }
            click("History")
            val logger = HistoryLogger(storage, settings)
            storage.clearProfileSwitchHistory()
            repeat(5) { logger.failure("history-fixture", "Example failure", IllegalStateException("Repeated example")) }
            assertEquals(1, storage.profileSwitchHistory.first().count { it.eventType == HistoryEventType.ERROR })
            logger.recovered("history-fixture")
            assertEquals(effective, storage.effectiveProfileState.first())
            storage.clearProfileSwitchHistory()
            await { nodes().any { it.text?.toString() == "No events logged yet." } }
            // Compose exposes the label separately from its disabled semantic parent.
            val shareLabel = nodes().first { it.text?.toString() == "Share" }
            val shareNodes = generateSequence(shareLabel) { it.parent }.toList()
            assertTrue("Empty History must disable Share: ${shareNodes.map { it.className to it.isEnabled }}",
                shareNodes.any { !it.isEnabled })
            val now = System.currentTimeMillis()
            val examples = listOf(
                HistoryEventType.ERROR to ("Profile could not be applied" to "CPU limit verification failed; the previous profile was retained."),
                HistoryEventType.SERVICE to ("App detection connected" to "PServer reconnected; app profiles can follow visible apps."),
                HistoryEventType.AUTO_TUNE to ("Auto Tune stopped" to "App A closed; restored the normal profile."),
                HistoryEventType.BOOT to ("Medium Underclock" to "Device started; restored the saved normal profile."),
                HistoryEventType.WAKE to ("Medium Underclock" to "Screen on; restored the previous profile."),
                HistoryEventType.SLEEP to ("Large Underclock" to "Screen off; applied the sleep profile."),
                HistoryEventType.APP to ("Medium Underclock" to "App C has no assigned profile; restored the normal profile."),
                HistoryEventType.PROFILE to ("Large Underclock" to "Selected manually in Profiles."),
            ).mapIndexed { index, (type, text) ->
                ProfileSwitchHistoryEntry(now - index * 60_000L, null, text.first, "Example · ${text.second}", type, false)
            }
            examples.asReversed().forEach { storage.appendProfileSwitchHistory(it, maxOf(limit, examples.size)) }
            await { nodes().any { it.text?.toString() == "Profile could not be applied" } }
            delay(500)
            screenshot("history-all-event-types.png")
            val list = nodes().first { it.packageName?.toString() == context.packageName && it.isScrollable }
            check(list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            await { nodes().any { it.text?.toString() == "Selected manually in Profiles." || it.text?.toString() == "Example · Selected manually in Profiles." } }
            delay(300)
            screenshot("history-all-event-types-bottom.png")
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(300)
            // Check the real content URI, MIME type, and grant before opening the system chooser.
            val send = createHistoryShareIntent(context, examples)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = requireNotNull(send.clipData).getItemAt(0).uri
            assertEquals("content", uri.scheme)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            HistoryEventType.entries.forEach { assertTrue(text.contains("[${it.label}]")) }
            File(context.filesDir, "history-example-export.txt").writeText(text)
            click("Share")
            await { nodes().any { it.text?.toString()?.contains("clustertune-history.txt", ignoreCase = true) == true &&
                it.packageName?.toString() != context.packageName } }
            delay(500)
            screenshot("history-android-share-sheet.png")
            // Thor can show system dialogs on either screen. Capture both physical displays.
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("screencap -d 4630946482288158084 -p")).use { input ->
                File(context.filesDir, "history-android-share-sheet-second-screen.png").outputStream().use { input.copyTo(it) }
            }
            shell("input -d 0 keyevent KEYCODE_BACK")
            assertEquals(effective, storage.effectiveProfileState.first())
        } finally {
            storage.clearProfileSwitchHistory()
            original.asReversed().forEach { storage.appendProfileSwitchHistory(it, maxOf(limit, original.size)) }
            scenario.close()
        }
    }
}
