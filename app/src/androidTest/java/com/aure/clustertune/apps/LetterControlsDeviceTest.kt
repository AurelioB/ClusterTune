package com.aure.clustertune.apps

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.AppContainer
import com.aure.clustertune.MainActivity
import com.aure.clustertune.model.TileInteractionBehavior
import com.aure.clustertune.overlay.OverlayHostService
import com.aure.clustertune.root.host.ApplyRequest
import com.aure.clustertune.root.host.hostTestShell
import com.aure.clustertune.update.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LetterControlsDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val container by lazy { AppContainer(context) }
    private val cases = JSONArray()
    private val failures = mutableListOf<String>()
    private var current: JSONObject? = null
    private val report = JSONObject().put("cases",cases)
    private val tile = "com.aure.clustertune/.tile.PerformanceTileService"
    private fun shell(command: String): String {
        val streams=inst.uiAutomation.executeShellCommandRwe(command)
        streams[1].close()
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use { it.readText() } +
            android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use { it.readText() }
    }

    @Test fun actualTileOverlayAndUpdateControls() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("letterControls") == "1")
        val settings = container.settingsStorage
        val original = settings.settings.first()
        val automationInfo=inst.uiAutomation.serviceInfo
        automationInfo.flags=automationInfo.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        inst.uiAutomation.serviceInfo=automationInfo
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        val snapshot = container.hostClient.readSnapshot().getOrThrow()
        try {
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            AppDetectionService.start(context)
            settings.persistAutoTuneEnabled(false)
            settings.persistLeftEdgeProfilePickerEnabled(false)
            launchC()
            scenario("04_real_recents_selects_letter_A") {
                shell("am start -W --display 0 -n com.aure.clustertune.fixture.a/com.aure.clustertune.testapps.LetterActivity")
                delay(700)
                val recents=LetterRecentsDriver()
                current!!.put("overview",recents.open())
                inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir,"letter-recents-probe.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                current!!.put("selectedCard",recents.select('a'))
                await("A reopened through Recents") { VisibleAppWindowEvents.snapshots.value.packages.contains("com.aure.clustertune.fixture.a") }
                launchC()
            }
            for (behavior in TileInteractionBehavior.entries) scenario("32_tile_$behavior") {
                launchC()
                settings.persistAutoTuneEnabled(behavior == TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD)
                settings.persistTileTapBehavior(behavior)
                OverlayHostService.dismiss(context); delay(300)
                val before = container.hostClient.readSnapshot().getOrThrow().state
                shell("cmd statusbar expand-settings"); delay(500)
                shell("cmd statusbar click-tile $tile")
                when (behavior) {
                    TileInteractionBehavior.SHOW_DIALOG, TileInteractionBehavior.SHOW_PROFILE_PICKER -> {
                        await("tile opens overlay") { "ClusterTune overlay" in titles() }
                        current!!.put("visibleTexts",JSONArray(nodes().mapNotNull { it.text?.toString() }.distinct()))
                        // These overlays deliberately keep the game focused, so Back is
                        // delivered to the game. Dismiss using the overlay's own control.
                        shell("input keyevent KEYCODE_BACK")
                        assertTrue("overlay keeps input focus with the underlying app", "ClusterTune overlay" in titles())
                        click("Close")
                        await("dismiss closes overlay") { "ClusterTune overlay" !in titles() }
                    }
                    TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD -> {
                        await("tile opens HUD") { "ClusterTune performance HUD" in titles() }
                        settings.persistAutoTuneEnabled(false)
                        await("disable closes HUD") { "ClusterTune performance HUD" !in titles() }
                    }
                    TileInteractionBehavior.CYCLE_PROFILES -> {
                        await("tile changes CPU limits") { container.hostClient.readSnapshot().getOrThrow().state.cpuMax != before.cpuMax }
                    }
                    TileInteractionBehavior.OPEN_APP -> {
                        await("tile opens main screen") { nodes().any { it.text?.toString() == "Profiles" } }
                    }
                }
                shell("cmd statusbar collapse")
                OverlayHostService.dismiss(context)
            }
            scenario("31_picker_apply_by_touch") {
                launchC(); OverlayHostService.showProfilePicker(context)
                await("picker ready") { nodes().any { it.text?.toString() == "Large Underclock" } }
                click("Large Underclock")
                val target = container.repository.observeState().first().displayProfiles.first { it.name == "Large Underclock" }
                await("touch applies profile") {
                    val s=container.hostClient.readSnapshot().getOrThrow()
                    s.capabilities.cpus.mapIndexed { i,c -> s.state.cpuMax[i] == target.maxFrequencies[c.id.removePrefix("policy").toInt()]?.toLong() }.all { it }
                }
                OverlayHostService.dismiss(context)
            }
            scenario("33_invisible_edge_handle_swipe") {
                launchC(); settings.persistLeftEdgeProfilePickerEnabled(true)
                settings.persistEdgeHandleOpacityPercent(0); OverlayHostService.showEdgeHandle(context)
                await("edge handle ready") { "ClusterTune profile edge handle" in titles() }
                var bounds=Rect()
                inst.runOnMainSync {
                    val v=WindowInspector.getGlobalWindowViews().first { (it.layoutParams as? WindowManager.LayoutParams)?.title == "ClusterTune profile edge handle" }
                    v.getGlobalVisibleRect(bounds)
                    val xy=IntArray(2); v.getLocationOnScreen(xy); bounds=Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)
                }
                shell("input -d 0 swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()+400} ${bounds.centerY()} 350")
                await("invisible handle opens picker") { "ClusterTune overlay" in titles() }
                OverlayHostService.dismiss(context)
                settings.persistLeftEdgeProfilePickerEnabled(false); OverlayHostService.hideEdgeHandle(context)
            }
            scenario("42_update_check_download_installer_handoff") {
                val main=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                bringMainToFront()
                await("update requested from foreground app") { nodes().any { it.text?.toString()=="Support" } }
                try {
                // An older reported version exercises the download path without replacing the tested app.
                val updater=AppUpdateManager(context,currentVersionName="0.0.0")
                val result=updater.checkForUpdates().getOrThrow()
                assertTrue(result is UpdateCheckResult.UpdateAvailable)
                val release=(result as UpdateCheckResult.UpdateAvailable).release
                current!!.put("release",release.tagName)
                val apk=updater.downloadApk(release).getOrThrow()
                try {
                    assertTrue("APK downloaded",apk.length()>0)
                    assertNotNull("Android can parse downloaded APK",context.packageManager.getPackageArchiveInfo(apk.path,0))
                    current!!.put("downloadedBytes",apk.length())
                    var launch=InstallLaunchResult.Started
                    main.onActivity { launch=AppUpdateManager(it).installApk(apk) }
                    current!!.put("installerResult",launch.name)
                    await("installer or permission screen opened") { shell("dumpsys activity activities").lineSequence().any {
                        (it.contains("ResumedActivity") || it.contains("topResumedActivity") || it.contains("Resumed:")) &&
                            (it.contains("packageinstaller",true) || it.contains("com.android.settings"))
                    } }
                    shell("input keyevent KEYCODE_BACK")
                } finally { apk.delete() }
                } finally { main.close() }
            }
            scenario("37_missing_overlay_access_message") {
                val before=shell("appops get ${context.packageName} SYSTEM_ALERT_WINDOW")
                val mode=Regex("SYSTEM_ALERT_WINDOW: ([a-z]+)").find(before)?.groupValues?.get(1) ?: "default"
                context.stopService(Intent(context,OverlayHostService::class.java))
                try {
                    shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW ignore")
                    val main=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    try {
                        bringMainToFront()
                        await("missing overlay access shown") { nodes().any { it.text?.toString()=="Overlay access" } }
                        assertFalse(android.provider.Settings.canDrawOverlays(context))
                    } finally { main.close() }
                } finally { shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode") }
            }
            // Revoking POST_NOTIFICATIONS kills this instrumented process. Test the actual
            // denied permission from an external device driver, then restore its grant.
            scenario("42_support_screen_and_link") {
                val main=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                await("main screen") { nodes().any { it.text?.toString()=="Support" } }
                click("Support")
                await("support screen") { nodes().any { it.text?.toString()=="Support ClusterTune" } }
                assertTrue(nodes().any { it.text?.toString()=="Open Ko-fi" })
                val intent=Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://ko-fi.com/J3J518XVKR"))
                assertNotNull("browser handles support link",intent.resolveActivity(context.packageManager))
                } finally { main.close() }
            }
            scenario("overlay_rapid_dismiss_restart") {
                settings.persistLeftEdgeProfilePickerEnabled(false)
                repeat(12) {
                    OverlayHostService.dismiss(context)
                    OverlayHostService.showEdgeHandle(context)
                    OverlayHostService.hideEdgeHandle(context)
                    delay(100)
                }
                delay(12_000)
                assertTrue("no overlay remains",titles().none { it.startsWith("ClusterTune") })
            }
        } finally {
            shell("cmd statusbar collapse")
            context.stopService(Intent(context,OverlayHostService::class.java))
            settings.persistAutoTuneEnabled(original.autoTuneEnabled)
            settings.persistTileTapBehavior(original.tileTapBehavior)
            settings.persistLeftEdgeProfilePickerEnabled(original.leftEdgeProfilePickerEnabled)
            settings.persistEdgeHandleOpacityPercent(original.edgeHandleOpacityPercent)
            container.hostClient.applyProfile(ApplyRequest(snapshot.state.cpuMax,snapshot.state.gpuMax,false,
                cpuIds=snapshot.capabilities.cpus.map { it.id },gpuId=snapshot.capabilities.gpu?.id,gpuMaxPath=snapshot.capabilities.gpu?.maxPath)).getOrThrow()
            if(original.leftEdgeProfilePickerEnabled) OverlayHostService.showEdgeHandle(context)
            report.put("finishedAtMs",System.currentTimeMillis()); save(); activity.close(); container.close()
        }
        assertTrue("failed controls: $failures",failures.isEmpty())
    }

    @Test fun prepareReboot() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("letterBoot") != null)
        val enabled=InstrumentationRegistry.getArguments().getString("letterBoot")=="enabled"
        context.stopService(Intent(context,OverlayHostService::class.java))
        context.stopService(Intent(context,AppDetectionService::class.java))
        context.stopService(Intent(context,com.aure.clustertune.sleep.SleepProfileMonitorService::class.java))
        container.settingsStorage.persistAutoTuneEnabled(false)
        container.settingsStorage.persistSleepProfileEnabled(false)
        container.settingsStorage.persistLeftEdgeProfilePickerEnabled(false)
        container.settingsStorage.persistApplyLastProfileOnBoot(enabled)
        container.profileStorage.appProfileAssignments.first().forEach { container.profileStorage.deleteAppProfileAssignment(it.packageName) }
        val s=container.repository.observeState().first()
        val target=s.displayProfiles.first { it.name=="Large Underclock" }
        container.repository.applyValues(s.policies,target.maxFrequencies,isReset=false,appliedDisplayProfileId=target.id,
            gpuPolicy=s.gpuPolicy,selectedGpuMaxFrequencyHz=target.gpuMaxFrequencyHz).getOrThrow()
        val snap=container.hostClient.readSnapshot().getOrThrow()
        File(context.filesDir,"letter-boot-prepared.json").writeText(JSONObject().put("enabled",enabled)
            .put("expectedCpuMax",JSONArray(snap.state.cpuMax))
            .put("stockCpuMax",JSONArray(snap.capabilities.cpus.map { it.stockMax }))
            .put("deviceSecure",context.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure)
            .put("timeMs",System.currentTimeMillis()).toString(2))
        container.close()
    }

    private fun launchC() {
        shell("am start --display 0 -W -f 0x10020000 --activity-single-top -n com.aure.clustertune.fixture.c/com.aure.clustertune.testapps.LetterActivity")
        SystemClock.sleep(700)
    }
    private fun bringMainToFront() {
        shell("am start --display 0 -W -f 0x10020000 --activity-single-top -n ${context.packageName}/.MainActivity")
        SystemClock.sleep(700)
    }
    private fun titles(): List<String> {
        var out=emptyList<String>(); inst.runOnMainSync { out=WindowInspector.getGlobalWindowViews().map { (it.layoutParams as? WindowManager.LayoutParams)?.title?.toString().orEmpty() } }; return out
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo?) { if(n==null)return; result+=n; repeat(n.childCount) { visit(n.getChild(it)) } }
        inst.uiAutomation.windows.forEach { visit(it.root) }; return result.filter { it.isVisibleToUser }
    }
    private fun click(text: String, contains: Boolean=false) {
        val n=nodes().first { listOfNotNull(it.text?.toString(),it.contentDescription?.toString()).any { v -> if(contains)v.contains(text) else v==text } }
        var target: AccessibilityNodeInfo?=n
        while(target!=null && !target.isClickable) target=target.parent
        check(target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true) { "cannot click $text" }
    }
    private suspend fun await(message: String, predicate: () -> Boolean) {
        val end=SystemClock.uptimeMillis()+12_000
        while(!predicate() && SystemClock.uptimeMillis()<end) delay(100)
        assertTrue(message,predicate())
    }
    private suspend fun scenario(name: String, block: suspend () -> Unit) {
        val filter=InstrumentationRegistry.getArguments().getString("letterControlFilter")
        if(filter!=null && filter.split(',').none { name.startsWith(it) }) return
        current=JSONObject().put("name",name).put("startedAtMs",System.currentTimeMillis()); cases.put(current)
        inst.sendStatus(2,Bundle().apply { putString("stream","\nLetter controls: $name\n") })
        try { block(); current!!.put("status","passed") }
        catch(t: org.junit.AssumptionViolatedException) {
            current!!.put("status","unavailable").put("reason",t.message)
                .put("visibleTexts",JSONArray(nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.distinct()))
        }
        catch(t: Throwable) {
            failures+=name; current!!.put("status","failed").put("error",t.stackTraceToString())
                .put("visibleTexts",JSONArray(nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }.distinct()))
                .put("windowTitles",JSONArray(titles()))
                .put("activities",shell("dumpsys activity activities"))
            inst.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.filesDir,"letter-control-failure-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        finally { current!!.put("finishedAtMs",System.currentTimeMillis()); save(); shell("cmd statusbar collapse"); OverlayHostService.dismiss(context) }
    }
    private fun save() { File(context.filesDir,"letter-controls.json").writeText(report.toString(2)) }
}
