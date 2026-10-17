package com.aure.clustertune.apps

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aure.clustertune.AppContainer
import com.aure.clustertune.autotune.AdaptiveTuneRuntime
import com.aure.clustertune.model.*
import com.aure.clustertune.notifications.AppForegroundNotification
import com.aure.clustertune.overlay.OverlayHostService
import com.aure.clustertune.root.host.ApplyRequest
import com.aure.clustertune.root.host.HostSnapshot
import com.aure.clustertune.root.host.hostTestShell
import com.aure.clustertune.sleep.SleepProfileMonitorService
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real package/window events and real clock writes; run only with -e letterApps 1. */
@RunWith(AndroidJUnit4::class)
class LetterAppsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ui get() = instrumentation.getUiAutomation(
        if (InstrumentationRegistry.getArguments().getString("letterLiveRefresh") == "1")
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0
    )
    private val context = instrumentation.targetContext
    private val container by lazy { AppContainer(context) }
    private val profiles get() = container.profileStorage
    private val settings get() = container.settingsStorage
    private val repo get() = container.repository
    private val host get() = container.hostClient
    private val cases = JSONArray()
    private val report = JSONObject().put("cases", cases)
    private var current: JSONObject? = null
    private var method = ""
    private var lastLaunchOutput = ""
    private var lastLaunchElapsed = 0L
    private val failures = mutableListOf<String>()
    private lateinit var normal: PerformanceProfile
    private lateinit var a: PerformanceProfile
    private lateinit var b: PerformanceProfile
    private var secondary = 1

    @Test fun selectRefreshMode() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("letterLiveRefresh") == "1")
        LetterRefreshModeDriver(ui).use { driver ->
            val result = driver.selectHz(InstrumentationRegistry.getArguments().getString("letterExpectedHz")!!.toInt())
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", result.toString()) })
        }
    }

    @Test fun pserverFunctions() = runBlocking { suite("pserver-stdout", full = true) }
    @Test fun rootAppSwitching() = runBlocking { suite("root-shell", full = false) }

    private suspend fun suite(methodId: String, full: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("letterApps") == "1")
        method = methodId
        for (letter in "abcd") context.packageManager.getApplicationInfo(pkg(letter), 0)
        val backup = Backup()
        backup.capture()
        val initial = host.readSnapshot().getOrThrow()
        report.put("method", method).put("startedAtMs", System.currentTimeMillis())
            .put("initial", state(initial))
        try {
            wake()
            stopServices()
            clearFixtures()
            settings.persistPrivilegedExecutionMethodId(method)
            settings.persistAutoTuneEnabled(false)
            settings.persistSleepProfileEnabled(false)
            settings.persistLeftEdgeProfilePickerEnabled(false)
            settings.persistProfileSwitchToastsEnabled(false)
            settings.persistProfileSwitchHistoryLimit(1000)
            container.privilegedExecutionResolver.setConfiguredMethodId(method)
            host.stop().getOrThrow()
            profiles.appProfileAssignments.first().forEach { profiles.deleteAppProfileAssignment(it.packageName) }
            profiles.clearSleepRestoreState()
            val tuner = repo.observeState().first()
            normal = tuner.displayProfiles.first { it.name == "Medium Underclock" }
            a = tuner.displayProfiles.first { it.name == "Large Underclock" }
            b = tuner.displayProfiles.first { it.name == "Small Underclock" }
            secondary = context.getSystemService(DisplayManager::class.java).displays.first { it.displayId != 0 }.displayId
            report.put("displays", JSONArray(listOf(0, secondary))).put("profiles", JSONObject()
                .put("normal", normal.name).put("a", a.name).put("b", b.name))
            setup()

            scenario("01_forward_A_B_C_A") {
                for ((letter, target) in listOf('a' to a, 'b' to b, 'c' to normal, 'a' to a)) step(letter, target)
            }
            scenario("02_reverse_A_C_B_A") {
                for ((letter, target) in listOf('a' to a, 'c' to normal, 'b' to b, 'a' to a)) step(letter, target)
            }
            scenario("03_twenty_round_trips") {
                repeat(20) { round ->
                    for ((letter, target) in listOf('a' to a, 'b' to b, 'c' to normal, 'a' to a)) {
                        show(letter); expect(target, setOf(letter), "$round-$letter")
                    }
                }
            }
            scenario("04_home_recents_back") {
                step('a', a)
                shell("input keyevent KEYCODE_HOME")
                expect(normal, emptySet(), "home")
                shell("input keyevent KEYCODE_APP_SWITCH")
                delay(500)
                // Reopen the existing task, then use Back to finish it.
                show('a'); expect(a, setOf('a'), "recent-task-reopened")
                shell("input keyevent KEYCODE_BACK")
                expect(normal, emptySet(), "back-finishes-task")
            }
            scenario("04b_actual_recents_reopen_after_home") {
                step('c',normal); step('b',b); step('a',a)
                shell("input -d 0 keyevent KEYCODE_HOME")
                expect(normal,emptySet(),"home-before-actual-recents")
                val recents=LetterRecentsDriver()
                current!!.put("overview",recents.open())
                captureRecents("home")
                current!!.put("selectedCard",recents.select('a'))
                expect(a,setOf('a'),"actual-card-A-after-home")
            }
            scenario("04c_actual_recents_A_B_C_A") {
                step('c',normal); step('b',b); step('a',a)
                val recents=LetterRecentsDriver()
                val selections=JSONArray();current!!.put("cardSelections",selections)
                repeat(3) { round ->
                    for((letter,target) in listOf('b' to b,'c' to normal,'a' to a)) {
                        val opened=recents.open()
                        if(round==0) captureRecents("before-$letter")
                        val selected=recents.select(letter).put("overview",opened).put("round",round)
                        selections.put(selected)
                        expect(target,setOf(letter),"actual-card-$round-$letter")
                    }
                }
            }
            scenario("04d_actual_recents_cancel_returns_to_A") {
                step('a',a)
                current!!.put("overview",LetterRecentsDriver().open())
                shell("input -d 0 keyevent KEYCODE_BACK")
                expect(a,setOf('a'),"cancel-overview-keeps-A")
            }
            scenario("04e_actual_recents_with_B_on_second_screen") {
                step('c',normal); step('a',a)
                show('b',secondary);expect(combined(a,b),setOf('a','b'),"A-B-before-overview")
                // Focus A without relocating B. Subsequent changes use card touches only.
                show('a');expect(combined(a,b),setOf('a','b'),"A-focused-B-on-second-screen")
                val recents=LetterRecentsDriver()
                val selections=JSONArray();current!!.put("cardSelections",selections)
                for(letter in listOf('c','a','c','a')) {
                    val opened=recents.open()
                    selections.put(recents.select(letter).put("overview",opened))
                    expect(if(letter=='a') combined(a,b) else b,setOf(letter,'b'),"actual-card-$letter-with-secondary-B")
                    expectDisplay('b',secondary);expectDisplay(letter,0)
                }
                awaitSingleScreen()
            }
            scenario("05_close_active_assigned_app") {
                step('a', a); shell("am force-stop ${pkg('a')}")
                expect(normal, emptySet(), "assigned-app-closed")
            }
            scenario("06_detection_while_clustertune_backgrounded") {
                step('b', b); step('c', normal); step('a', a)
            }
            scenario("07_same_profile_between_two_apps") {
                assign('b', a)
                repeat(3) { step('a', a); step('b', a) }
                assign('b', b)
            }
            scenario("08_custom_cpu_gpu_assignment") {
                val gpu = tuner.gpuPolicy!!.supportedFrequenciesHz.sorted().let { it[it.size / 2] }
                val custom = a.copy(id = "manual", name = "Custom test", gpuMaxFrequencyHz = gpu)
                profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('a'), "A", customMaxFrequencies = a.maxFrequencies,
                    customGpuMaxFrequencyHz = gpu))
                step('a', custom); step('c', normal); assign('a', a)
            }
            scenario("09_change_assignment_while_visible") {
                step('a', a); assign('a', b); expect(b, setOf('a'), "assignment-edited"); assign('a', a)
                expect(a, setOf('a'), "assignment-restored")
            }
            scenario("10_remove_visible_assignment") {
                step('a', a); profiles.deleteAppProfileAssignment(pkg('a'))
                expect(normal, setOf('a'), "assignment-removed"); assign('a', a)
            }
            scenario("11_notification_shade_and_overlay") {
                step('a', a)
                shell("cmd statusbar expand-notifications"); delay(500)
                expect(a, setOf('a'), "notification-shade")
                shell("cmd statusbar collapse")
                OverlayHostService.showCompactTuner(context)
                await("quick tuner attached") { "ClusterTune overlay" in windowTitles() }
                expect(a, setOf('a'), "quick-tuner-over-A")
                OverlayHostService.dismiss(context); delay(300)
            }
            scenario("12_launchable_and_recent_dummy_apps") {
                step('a', a); step('b', b); step('c', normal)
                val listed = container.installedAppRepository.listLaunchableApps().map { it.packageName }
                assertTrue("all four dummy apps in picker", "abcd".all { pkg(it) in listed })
                val recent = container.installedAppRepository.listRecentActiveApps().map { it.packageName }
                assertTrue("recent A/B/C are present", "abc".all { pkg(it) in recent })
                assertTrue("recent C before B before A", recent.indexOf(pkg('c')) < recent.indexOf(pkg('b')) && recent.indexOf(pkg('b')) < recent.indexOf(pkg('a')))
                current!!.put("recentDummyApps", JSONArray(recent.filter { it.startsWith("com.aure.clustertune.fixture.") }))
            }
            scenario("13_two_screens_A_B") {
                show('a', 0); show('b', secondary)
                expect(combined(a, b), setOf('a', 'b'), "A+B")
                expectDisplay('a', 0); expectDisplay('b', secondary)
            }
            scenario("13b_crossed_cpu_gpu_requests") {
                val first=tuner.policies.first().id
                val customA=a.copy(id="custom-a",name="Crossed A",maxFrequencies=a.maxFrequencies+(first to b.maxFrequencies.getValue(first)),
                    gpuMaxFrequencyHz=tuner.gpuPolicy!!.supportedFrequenciesHz.sorted().let { it[it.size/2] })
                val customB=b.copy(id="custom-b",name="Crossed B",maxFrequencies=b.maxFrequencies+(first to a.maxFrequencies.getValue(first)),
                    gpuMaxFrequencyHz=tuner.gpuPolicy.observedMaxFrequencyHz)
                try {
                    for((letter,target) in listOf('a' to customA,'b' to customB)) profiles.saveAppProfileAssignment(
                        AppProfileAssignment(pkg(letter),letter.uppercase(),customMaxFrequencies=target.maxFrequencies,customGpuMaxFrequencyHz=target.gpuMaxFrequencyHz))
                    show('a',0); show('b',secondary); expect(combined(customA,customB),setOf('a','b'),"crossed-A+B")
                    show('d',secondary); expect(customA,setOf('a','d'),"remove-crossed-B")
                    show('b',secondary); show('c',0); expect(customB,setOf('c','b'),"remove-crossed-A")
                } finally { assign('a',a); assign('b',b) }
                show('a',0); show('b',secondary); expect(combined(a,b),setOf('a','b'),"standard-A+B")
            }
            scenario("14_independent_unassigned_C_D") {
                show('c', 0); expect(b, setOf('c', 'b'), "C+B")
                show('d', secondary); expect(normal, setOf('c', 'd'), "C+D")
                show('a', 0); expect(a, setOf('a', 'd'), "A+D")
                show('b', secondary); expect(combined(a, b), setOf('a', 'b'), "A+B-again")
            }
            scenario("15_swap_physical_screens") {
                show('c', 0); show('d', secondary)
                show('a', secondary); show('b', 0)
                expect(combined(a, b), setOf('a','b'), "B+A")
                expectDisplay('a', secondary); expectDisplay('b', 0)
                show('c', secondary); expect(b, setOf('b', 'c'), "B+C")
                show('d', 0); expect(normal, setOf('d', 'c'), "D+C")
            }
            scenario("16_sleep_disabled") {
                awaitSingleScreen(); step('a', a)
                repeat(2) {
                    shell("input keyevent KEYCODE_SLEEP"); delay(600); assertClocks(a)
                    wake(); expect(a, setOf('a'), "wake-with-sleep-off")
                }
            }
            scenario("17_sleep_profile_then_assigned_app") {
                step('a', a)
                settings.persistSleepProfile(true, b.id); SleepProfileMonitorService.start(context)
                delay(500); shell("input keyevent KEYCODE_SLEEP")
                await("sleep profile clocks") { clocksMatch(b) }
                record("sleep", host.readSnapshot().getOrThrow())
                wake(); expect(a, setOf('a'), "wake-restores-assigned-A")
                context.stopService(Intent(context, SleepProfileMonitorService::class.java))
                settings.persistSleepProfileEnabled(false); profiles.clearSleepRestoreState()
            }
            scenario("18_repeated_sleep_wake_switches") {
                settings.persistSleepProfile(true, a.id); SleepProfileMonitorService.start(context); delay(400)
                repeat(3) {
                    step('b', b); shell("input keyevent KEYCODE_SLEEP")
                    await("sleep reaches A") { clocksMatch(a) }; wake(); expect(b, setOf('b'), "cycle-$it")
                    step('c', normal)
                }
                context.stopService(Intent(context, SleepProfileMonitorService::class.java))
                settings.persistSleepProfileEnabled(false); profiles.clearSleepRestoreState()
            }
            if (full) fullFunctions(tuner)
            scenario("29b_current_refresh_mode_targets_and_frames") { currentRefreshModeAutoTune() }
            if(InstrumentationRegistry.getArguments().getString("letterLiveRefresh")=="1") {
                scenario("29c_oem_button_live_refresh_change") { liveRefreshModeAutoTune() }
            }
            scenario("38_confirm_selected_privileged_method") {
                assertEquals(method, host.selectedMethodId)
                assertEquals(method, settings.settings.first().privilegedExecutionMethodId)
            }
            scenario("39_host_reconnect_with_A_visible") {
                awaitSingleScreen(); assign('a', a); step('a', a)
                val epoch = host.readSnapshot().getOrThrow().epoch
                host.stop().getOrThrow()
                await("detector sees host death") { !AppDetectionState.status.value.available }
                await("host reconnect", 30_000) { AppDetectionState.status.value.available }
                assertNotEquals(epoch,host.readSnapshot().getOrThrow().epoch)
                expect(a, setOf('a'), "reconnected-with-A"); step('b', b); step('c', normal)
            }
            scenario("39b_reconnect_with_simultaneous_clock_reads") {
                awaitSingleScreen(); step('a',a)
                val epoch=host.readSnapshot().getOrThrow().epoch
                host.stop().getOrThrow()
                kotlinx.coroutines.coroutineScope {
                    val reader=launch(kotlinx.coroutines.Dispatchers.IO) {
                        repeat(150) { host.readSnapshot(); delay(100) }
                    }
                    try {
                        await("detector notices shutdown") { !AppDetectionState.status.value.available }
                        await("detector reconnects with another consumer",30_000) { AppDetectionState.status.value.available }
                        expect(a,setOf('a'),"recovered-with-live-clock-reader")
                        assertNotEquals(epoch,host.readSnapshot().getOrThrow().epoch)
                    } finally { reader.cancel(); reader.join() }
                }
            }
            scenario("40_detection_start_with_existing_A") {
                context.stopService(Intent(context, AppDetectionService::class.java))
                await("detector stopped") { !running(AppDetectionService::class.java) }
                show('a'); AppDetectionService.start(context)
                expect(a, setOf('a'), "detector-started-after-A")
            }
        } finally {
            wake(); stopServices(); clearFixtures()
            backup.restore()
            container.privilegedExecutionResolver.setConfiguredMethodId(backup.savedSettings.privilegedExecutionMethodId)
            host.applyProfile(ApplyRequest(initial.state.cpuMax, initial.state.gpuMax, false,
                cpuIds = initial.capabilities.cpus.map { it.id }, gpuId = initial.capabilities.gpu?.id,
                gpuMaxPath = initial.capabilities.gpu?.maxPath)).getOrThrow()
            report.put("restored", state(host.readSnapshot().getOrThrow())).put("finishedAtMs", System.currentTimeMillis())
            host.stop()
            if (backup.detectorRunning) AppDetectionService.start(context)
            if (backup.sleepRunning) SleepProfileMonitorService.start(context)
            if (backup.savedSettings.leftEdgeProfilePickerEnabled) OverlayHostService.showEdgeHandle(context)
            save(); container.close()
        }
        assertTrue("Failed scenarios: $failures; see letter-apps-$method.json", failures.isEmpty())
    }

    private suspend fun fullFunctions(tuner: TunerState) {
        scenario("19_manual_profiles_and_stock") {
            awaitSingleScreen(); show('c')
            val stock = tuner.displayProfiles.first { it.id == ProfileStateResolver.STOCK_PROFILE_ID }
            for (target in listOf(a, b, normal, stock, normal)) { applyNormal(target); expect(target, setOf('c'), target.name) }
        }
        scenario("20_profile_create_edit_reorder_delete") {
            repo.createUserProfile("Letter matrix temporary", a.maxFrequencies, a.gpuMaxFrequencyHz)
            val created = profiles.profiles.first().single { it.name == "Letter matrix temporary" }
            repo.updateProfile(created.id, "Letter matrix edited", b.maxFrequencies, b.gpuMaxFrequencyHz)
            val edited = profiles.profiles.first().single { it.id == created.id }
            assertEquals("Letter matrix edited", edited.name); assertEquals(b.maxFrequencies, edited.maxFrequencies)
            repo.moveProfile(created.id, -1)
            assign('a', edited); step('a', edited)
            repo.deleteProfile(created.id)
            assertFalse(profiles.profiles.first().any { it.id == created.id })
            assertFalse(profiles.appProfileAssignments.first().any { it.packageName == pkg('a') })
            expect(normal, setOf('a'), "deleted-assigned-profile"); assign('a', a)
        }
        scenario("21_export_import_invalid_file") {
            val json = repo.exportProfilesJson()
            val before = repo.observeState().first().displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
            assertTrue(repo.importProfilesJson(json) > 0)
            val after = repo.observeState().first().displayProfiles.filter { it.source != ProfileSource.VIRTUAL }
            assertEquals(before.map { Triple(it.id, it.maxFrequencies, it.gpuMaxFrequencyHz) }, after.map { Triple(it.id, it.maxFrequencies, it.gpuMaxFrequencyHz) })
            val stored = profiles.profiles.first()
            runCatching { repo.importProfilesJson("{invalid") }
            assertEquals(stored, profiles.profiles.first())
        }
        scenario("22_reset_defaults") {
            repo.createUserProfile("Letter reset temporary", a.maxFrequencies)
            repo.resetProfilesToDefault()
            assertTrue(profiles.profiles.first().isEmpty())
            assertTrue(repo.observeState().first().displayProfiles.any { it.name == a.name })
            assign('a', a); assign('b', b); applyNormal(normal)
        }
        scenario("23_disabled_autotune_assignment_dormant") {
            profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('d'), "D", autoTuneTargetFps = 30))
            step('d', normal); delay(1_500)
            assertFalse(AdaptiveTuneRuntime.state.value.active)
        }
        scenario("24_manual_autotune_start_stop") {
            context.stopService(Intent(context, AppDetectionService::class.java))
            await("detector stopped for manual runner") { !running(AppDetectionService::class.java) }
            settings.persistAutoTuneEnabled(true)
            show('c', render = true)
            try {
                kotlinx.coroutines.coroutineScope {
                    val job = launch {
                        com.aure.clustertune.autotune.AdaptiveTuneSessionRunner(repo).run(pkg('c'), "C", 30)
                    }
                    try { expectAuto('c', 30); delay(3_000); record("manual-auto-running",host.readSnapshot().getOrThrow()) }
                    finally { job.cancel(); job.join() }
                }
                await("manual stop restores normal") { !AdaptiveTuneRuntime.state.value.active && clocksMatch(normal) }
            } finally { settings.persistAutoTuneEnabled(false); AppDetectionService.start(context,retry=true) }
        }
        scenario("25_auto_fixed_unassigned_auto") {
            settings.persistAutoTuneEnabled(true)
            profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('a'), "A", autoTuneTargetFps = 30))
            show('a', render = true); expectAuto('a', 30); delay(5_000); record("auto-A", host.readSnapshot().getOrThrow())
            step('b', b); assertFalse(AdaptiveTuneRuntime.state.value.active)
            step('c', normal); show('a', render = true); expectAuto('a', 30)
        }
        scenario("26_two_autotune_targets") {
            profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('b'), "B", autoTuneTargetFps = 45))
            show('b', render = true); expectAuto('b', 45)
            show('a', render = true); expectAuto('a', 30)
            assign('b', b)
        }
        scenario("27_manual_override_and_restart") {
            show('a', render = true); expectAuto('a', 30)
            applyNormal(normal); delay(1_500); assertFalse(AdaptiveTuneRuntime.state.value.active); assertClocks(normal)
            step('c', normal); show('a', render = true); expectAuto('a', 30)
        }
        scenario("28_disable_active_autotune") {
            settings.persistAutoTuneEnabled(false)
            await("disabled Auto Tune stops") { !AdaptiveTuneRuntime.state.value.active && clocksMatch(normal) }
            assertTrue(profiles.appProfileAssignments.first().any { it.packageName == pkg('a') && it.isAutoTune })
            assign('a', a)
        }
        scenario("29_static_frames_then_redraw_same_letter") {
            settings.persistAutoTuneEnabled(true)
            profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('a'), "A", autoTuneTargetFps = 30))
            show('c'); expect(normal, setOf('c'), "before-static")
            show('a', render = false); expectAuto('a', 30); delay(3_000)
            record("static-letter", host.readSnapshot().getOrThrow())
            show('a', render = true)
            await("real FPS samples from redraw", 20_000) { AdaptiveTuneRuntime.state.value.measuredFps?.let { it > 0 } == true }
            current!!.put("measuredFps", AdaptiveTuneRuntime.state.value.measuredFps)
                .put("frameBackend", AdaptiveTuneRuntime.state.value.frameBackend)
            show('c'); expect(normal, setOf('c'), "auto-exit")
            settings.persistAutoTuneEnabled(false); assign('a', a)
        }
        scenario("31_quick_tuner_and_picker_over_A_B") {
            for ((letter, target) in listOf('a' to a, 'b' to b)) {
                step(letter, target)
                OverlayHostService.showCompactTuner(context)
                await("tuner visible") { "ClusterTune overlay" in windowTitles() }
                expect(target, setOf(letter), "tuner-$letter")
                OverlayHostService.dismiss(context); delay(250)
                OverlayHostService.showProfilePicker(context)
                await("picker visible") { "ClusterTune overlay" in windowTitles() }
                expect(target, setOf(letter), "picker-$letter")
                OverlayHostService.dismiss(context); delay(250)
            }
        }
        scenario("32_cycle_profiles") {
            step('c', normal)
            val count = repo.observeState().first().displayProfiles.size
            repeat(count + 1) {
                val selected = repo.cycleTileProfile().getOrThrow()
                assertClocks(selected)
            }
            applyNormal(normal)
        }
        scenario("33_edge_handle_settings_and_window") {
            settings.persistLeftEdgeProfilePickerEnabled(true)
            settings.persistEdgeHandleHeightDp(100); settings.persistEdgeHandleThicknessDp(8)
            settings.persistEdgeHandleVerticalPositionPercent(60); settings.persistEdgeHandleOpacityPercent(0)
            OverlayHostService.showEdgeHandle(context)
            await("edge handle attached") { windowTitles().any { it.contains("edge", true) } }
            assertEquals(0, settings.settings.first().edgeHandleOpacityPercent)
            settings.persistLeftEdgeProfilePickerEnabled(false); OverlayHostService.hideEdgeHandle(context)
            await("edge handle removed") { windowTitles().none { it.contains("edge", true) } }
        }
        scenario("34_hud_tracks_A_B_and_closes") {
            settings.persistAutoTuneEnabled(true); step('a', a)
            OverlayHostService.togglePerformanceHud(context)
            await("HUD attached") { windowTitles().any { it.contains("HUD") } }
            step('b', b)
            settings.persistAutoTuneEnabled(false)
            await("HUD closes when disabled") { windowTitles().none { it.contains("HUD") } }
        }
        scenario("35_history_limit_and_toast_setting") {
            settings.persistProfileSwitchHistoryLimit(3)
            settings.persistProfileSwitchToastsEnabled(true)
            for (letter in "abcab") step(letter, if (letter == 'a') a else if (letter == 'b') b else normal)
            await("history limit enforced") { profiles.profileSwitchHistory.first().size <= 3 }
            val history = profiles.profileSwitchHistory.first()
            assertEquals(3, history.size); assertTrue(history.zipWithNext().all { (x,y) -> x.timestampMillis >= y.timestampMillis })
            settings.persistProfileSwitchToastsEnabled(false); settings.persistProfileSwitchHistoryLimit(1000)
        }
        scenario("35b_toasts_shown_and_disabled") {
            awaitSingleScreen(); step('c',normal)
            // Android limits background toast frequency. Let earlier switch messages expire
            // from that quota before checking two visible notifications deliberately.
            delay(45_000)
            val events=java.util.concurrent.LinkedBlockingQueue<android.view.accessibility.AccessibilityEvent>()
            ui.setOnAccessibilityEventListener { event ->
                if(event.eventType==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
                    events.offer(android.view.accessibility.AccessibilityEvent.obtain(event))
            }
            fun toastSeen(name: String): Boolean {
                val deadline=SystemClock.uptimeMillis()+5_000
                while(SystemClock.uptimeMillis()<deadline) {
                    val event=events.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val matches=event.text.any { it.toString().contains(name) }
                    event.recycle()
                    if(matches)return true
                }
                return false
            }
            try {
                settings.persistProfileSwitchToastsEnabled(true)
                step('a',a)
                assertTrue("enabled switch toast is announced",toastSeen(a.name))
                delay(150)
                ui.takeScreenshot()?.let { bitmap ->
                    File(context.filesDir,"letter-toast-visible.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                settings.persistProfileSwitchToastsEnabled(false); events.clear()
                step('b',b); delay(1_500)
                assertTrue("disabled switch toast is absent",events.none { it.text.any { text -> text.toString().contains(b.name) } })
                settings.persistProfileSwitchToastsEnabled(true); events.clear()
                step('c',normal)
                assertTrue("normal-profile toast is announced",toastSeen(normal.name))
            } finally {
                ui.setOnAccessibilityEventListener(null)
                settings.persistProfileSwitchToastsEnabled(false)
                events.forEach { it.recycle() }
            }
        }
        scenario("36_appearance_persistence") {
            val before = host.readSnapshot().getOrThrow().state
            settings.persistPresetAccentColor(0xFF006E1C.toInt()); settings.persistDisplayFrequenciesAsPercent(true)
            val saved = com.aure.clustertune.data.SettingsStorage(context).settings.first()
            assertEquals(0xFF006E1C.toInt(), saved.accentColor); assertTrue(saved.displayFrequenciesAsPercent)
            assertEquals(before.cpuMax, host.readSnapshot().getOrThrow().state.cpuMax)
        }
        scenario("37_shared_notification_and_no_legacy_permission") {
            val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
            assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.PACKAGE_USAGE_STATS"))
            assertTrue(info.services.orEmpty().none { it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE" })
            assertTrue(Settings.canDrawOverlays(context))
            val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications
            assertEquals(1, notifications.count { it.id == AppForegroundNotification.ID })
        }
    }

    /** Uses the real OEM-selected modes; never changes refresh settings or display preferences. */
    private suspend fun currentRefreshModeAutoTune() {
        val expected=InstrumentationRegistry.getArguments().getString("letterExpectedHz")?.toInt()
        val manager=context.getSystemService(DisplayManager::class.java)
        val checks=JSONArray();current!!.put("checks",checks)
        try {
            settings.persistAutoTuneEnabled(true)
            for(displayId in listOf(0,secondary)) {
                val display=manager.getDisplay(displayId)
                val hz=kotlin.math.round(display.mode.refreshRate).toInt()
                if(expected!=null) assertEquals("physical display $displayId",expected,hz)
                assertTrue("display API matches physical mode",kotlin.math.abs(display.refreshRate-hz)<1)
                if(displayId!=0) show('c',0)
                for(savedTarget in listOf(hz,hz/2,hz*2)) {
                    profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('a'),"A",autoTuneTargetFps=savedTarget))
                    show('a',displayId,render=true);expectDisplay('a',displayId)
                    val runtimeTarget=minOf(savedTarget,hz)
                    expectAuto('a',runtimeTarget)
                    val check=JSONObject().put("displayId",displayId).put("physicalHz",display.mode.refreshRate)
                        .put("apiHz",display.refreshRate).put("savedTarget",savedTarget).put("runtimeTarget",runtimeTarget)
                        .put("frames",JSONArray())
                    checks.put(check);save()
                    var last=AdaptiveTuneRuntime.state.value.sampleTimestampNanos
                    repeat(3) {
                        await("fresh FPS sample on display $displayId at $hz Hz",20_000) {
                            AdaptiveTuneRuntime.state.value.let { state -> state.active && state.targetFps==runtimeTarget &&
                                state.sampleTimestampNanos!=null && state.sampleTimestampNanos!=last &&
                                !state.frameStale && (state.measuredFps?:0.0)>0 }
                        }
                        val state=AdaptiveTuneRuntime.state.value;last=state.sampleTimestampNanos
                        check.getJSONArray("frames").put(JSONObject().put("sampleNanos",last)
                            .put("fps",state.measuredFps).put("backend",state.frameBackend).put("status",state.status)
                            .put("confidence",state.frameConfidence));save()
                    }
                    assertEquals("saved target remains portable",savedTarget,
                        profiles.appProfileAssignments.first().single { it.packageName==pkg('a') }.autoTuneTargetFps)
                    assertEquals("physical mode unchanged",hz,kotlin.math.round(manager.getDisplay(displayId).mode.refreshRate).toInt())
                }
            }
            settings.persistAutoTuneEnabled(false)
            await("Auto Tune stops") { !AdaptiveTuneRuntime.state.value.active }
            expect(normal,setOf('a','c'),"refresh-mode-disable-restores-normal")
        } finally {
            settings.persistAutoTuneEnabled(false)
            awaitSingleScreen();assign('a',a);applyNormal(normal)
        }
    }

    private suspend fun liveRefreshModeAutoTune() {
        val driver=LetterRefreshModeDriver(ui)
        val initial=kotlin.math.round(driver.physicalHz()).toInt()
        check(initial in listOf(60,120)) { "live mode fixture requires a 60/120 Hz panel" }
        val transitions=JSONArray();current!!.put("transitions",transitions)
        try {
            settings.persistAutoTuneEnabled(true)
            profiles.saveAppProfileAssignment(AppProfileAssignment(pkg('a'),"A",autoTuneTargetFps=120))
            show('a',0,render=true);expectAuto('a',initial)
            for(hz in listOf(if(initial==120)60 else 120,initial)) {
                val action=driver.selectHz(hz);transitions.put(action);save()
                // Do not relaunch or edit A: a real display event must update its running target.
                expectAuto('a',hz);expectDisplay('a',0)
                val state=AdaptiveTuneRuntime.state.value
                action.put("runtimeTarget",state.targetFps).put("savedTarget",120)
                val frames=JSONArray();action.put("frames",frames)
                var last=state.sampleTimestampNanos
                repeat(3) {
                    await("fresh frames after OEM mode change to $hz Hz",20_000) {
                        AdaptiveTuneRuntime.state.value.let { s -> s.active && s.targetFps==hz &&
                            s.sampleTimestampNanos!=null && s.sampleTimestampNanos!=last &&
                            !s.frameStale && (s.measuredFps?:0.0)>0 }
                    }
                    val sample=AdaptiveTuneRuntime.state.value;last=sample.sampleTimestampNanos
                    frames.put(JSONObject().put("sampleNanos",last).put("fps",sample.measuredFps)
                        .put("backend",sample.frameBackend).put("targetFps",sample.targetFps));save()
                }
                assertEquals(120,profiles.appProfileAssignments.first().single { it.packageName==pkg('a') }.autoTuneTargetFps)
            }
        } finally {
            try {
                try {
                    if(kotlin.math.abs(driver.physicalHz()-initial)>=1) driver.selectHz(initial)
                } finally { driver.close() }
            } finally {
                settings.persistAutoTuneEnabled(false);awaitSingleScreen();assign('a',a);applyNormal(normal)
            }
        }
    }

    private suspend fun setup() {
        assign('a', a); assign('b', b); applyNormal(normal)
        AppDetectionService.start(context, retry = true)
        await("app detector available") { AppDetectionState.status.value.available }
        awaitSingleScreen()
    }
    private fun captureRecents(label: String) {
        ui.takeScreenshot()?.let { bitmap ->
            File(context.filesDir,"letter-recents-$method-$label.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            bitmap.recycle()
        }
    }
    private suspend fun awaitSingleScreen() {
        shell("input -d $secondary keyevent KEYCODE_HOME")
        shell("input -d 0 keyevent KEYCODE_HOME")
        await("dummy apps no longer visible") { letters().isEmpty() }
    }
    private suspend fun assign(letter: Char, profile: PerformanceProfile) {
        profiles.saveAppProfileAssignment(AppProfileAssignment(pkg(letter), letter.uppercase(), profileId = profile.id))
    }
    private suspend fun applyNormal(target: PerformanceProfile) {
        val s = repo.observeState().first()
        repo.applyValues(s.policies, target.maxFrequencies, isReset = target.id == ProfileStateResolver.STOCK_PROFILE_ID,
            appliedDisplayProfileId = target.id, gpuPolicy = s.gpuPolicy, selectedGpuMaxFrequencyHz = target.gpuMaxFrequencyHz).getOrThrow()
    }
    private fun pkg(letter: Char) = "com.aure.clustertune.fixture.$letter"
    private fun shell(command: String): String {
        val streams = ui.executeShellCommandRwe(command)
        streams[1].close()
        val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[0]).bufferedReader().use { it.readText() } +
            android.os.ParcelFileDescriptor.AutoCloseInputStream(streams[2]).bufferedReader().use { it.readText() }
        check(!result.contains("Exception occurred while executing") && !result.contains("Unknown option:")) { "$command: $result" }
        return result
    }
    private fun show(letter: Char, display: Int = 0, render: Boolean = false) {
        val started = SystemClock.uptimeMillis()
        lastLaunchOutput = shell("am start -W --display $display -f 0x10020000 --activity-single-top -n ${pkg(letter)}/com.aure.clustertune.testapps.LetterActivity --ez renderFrames $render")
        lastLaunchElapsed = SystemClock.uptimeMillis() - started
        check(!lastLaunchOutput.contains("Error:")) { lastLaunchOutput }
    }
    private suspend fun step(letter: Char, target: PerformanceProfile) { show(letter); expect(target, setOf(letter), "show-$letter") }
    private fun letters() = VisibleAppWindowEvents.snapshots.value.packages.mapNotNull {
        it.takeIf { it.startsWith("com.aure.clustertune.fixture.") }?.last()
    }.toSet()
    private suspend fun expect(target: PerformanceProfile, visible: Set<Char>, label: String) {
        val start = SystemClock.uptimeMillis()
        await("$label expected ${target.name} with $visible; visible=${VisibleAppWindowEvents.snapshots.value}") {
            if(letters() != visible || !clocksMatch(target)) false else {
                val assigned=profiles.appProfileAssignments.first().filter { it.packageName in visible.map(::pkg) && !it.isAutoTune }
                val effective=profiles.effectiveProfileState.first()
                if(assigned.isEmpty()) effective?.source in setOf(EffectiveProfileSource.NORMAL,EffectiveProfileSource.STOCK,EffectiveProfileSource.MANUAL)
                else effective?.contributingPackageNames?.toSet()==assigned.map { it.packageName }.toSet() &&
                    effective.source==if(assigned.size>1)EffectiveProfileSource.COMBINED else EffectiveProfileSource.APP
            }
        }
        record(label, host.readSnapshot().getOrThrow(), SystemClock.uptimeMillis() - start)
    }
    private suspend fun expectDisplay(letter: Char, display: Int) {
        await("$letter on display $display") { VisibleAppWindowEvents.snapshots.value.windowsByDisplay[display].orEmpty().any { it.packageName == pkg(letter) } }
    }
    private suspend fun expectAuto(letter: Char, fps: Int) {
        await("Auto Tune $letter / $fps; state=${AdaptiveTuneRuntime.state.value}", 20_000) {
            AdaptiveTuneRuntime.state.value.let { it.active && it.packageName == pkg(letter) && it.targetFps == fps && it.sampleTimestampNanos != null }
        }
        record("auto-$letter-$fps", host.readSnapshot().getOrThrow())
    }
    private fun clocksMatch(target: PerformanceProfile): Boolean {
        val snap = host.readSnapshot().getOrThrow()
        return snap.capabilities.cpus.mapIndexed { index, cpu -> target.maxFrequencies[cpu.id.removePrefix("policy").toInt()]?.toLong() == snap.state.cpuMax[index] }.all { it } &&
            (target.gpuMaxFrequencyHz == null || target.gpuMaxFrequencyHz.toLong() == snap.state.gpuMax)
    }
    private fun assertClocks(target: PerformanceProfile) { assertTrue("actual clocks must match ${target.name}: ${state(host.readSnapshot().getOrThrow())}", clocksMatch(target)) }
    private fun combined(x: PerformanceProfile, y: PerformanceProfile) = x.copy(id = "combined", name = "Combined",
        maxFrequencies = x.maxFrequencies.mapValues { (id, value) -> maxOf(value, y.maxFrequencies.getValue(id)) },
        gpuMaxFrequencyHz = listOfNotNull(x.gpuMaxFrequencyHz,y.gpuMaxFrequencyHz).maxOrNull())
    private fun state(s: HostSnapshot) = JSONObject().put("epoch",s.epoch).put("cpuMax",JSONArray(s.state.cpuMax))
        .put("cpuMin",JSONArray(s.state.cpuMin)).put("gpuMax",s.state.gpuMax)
    private suspend fun record(label: String, snapshot: HostSnapshot, elapsed: Long? = null) {
        val windows = VisibleAppWindowEvents.snapshots.value.windowsByDisplay.values.flatten()
        val item = state(snapshot).put("label",label).put("timeMs",System.currentTimeMillis()).put("settleMs",elapsed)
            .put("launchMs",lastLaunchElapsed)
            .put("visible",JSONArray(windows.map { JSONObject().put("package",it.packageName).put("display",it.displayId).put("focused",it.isFocused) }))
            .put("effectiveProfile",profiles.effectiveProfileState.first()?.name)
            .put("autoTuneActive",AdaptiveTuneRuntime.state.value.active)
        current?.getJSONArray("samples")?.put(item); save()
    }
    private suspend fun scenario(name: String, action: suspend () -> Unit) {
        val filter = InstrumentationRegistry.getArguments().getString("letterCaseFilter")
        if (filter != null && filter.split(',').none { name.startsWith(it) }) return
        current = JSONObject().put("name",name).put("samples",JSONArray()).put("startedAtMs",System.currentTimeMillis())
        cases.put(current); save()
        instrumentation.sendStatus(2, Bundle().apply { putString("stream","\nLetter apps ($method): $name\n") })
        try { action(); current!!.put("status","passed") }
        catch (t: Throwable) {
            failures += name; current!!.put("status","failed").put("error",t.stackTraceToString())
            current!!.put("lastLaunchOutput",lastLaunchOutput).put("detector",AppDetectionState.status.value.toString())
                .put("runtime",AdaptiveTuneRuntime.state.value.toString())
                .put("detectionRunning",running(AppDetectionService::class.java))
                .put("threads",Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread,stack) ->
                    thread.name+"\n"+stack.joinToString("\n")
                })
            runCatching { record("failure-state",host.readSnapshot().getOrThrow()) }
            current!!.put("actualTasks",shell("dumpsys activity activities").lineSequence().filter {
                it.contains("clustertune.fixture") || it.contains("mResumedActivity") || it.contains("topResumedActivity")
            }.joinToString("\n"))
            // Continue from a known baseline so one failure does not invalidate every later scenario.
            runCatching {
                wake(); OverlayHostService.dismiss(context)
                context.stopService(Intent(context,SleepProfileMonitorService::class.java))
                settings.persistSleepProfileEnabled(false); settings.persistAutoTuneEnabled(false)
                profiles.clearSleepRestoreState(); assign('a',a); assign('b',b)
                awaitSingleScreen(); applyNormal(normal); AppDetectionService.start(context,retry=true)
            }
        } finally {
            current!!.put("finishedAtMs",System.currentTimeMillis())
                .put("history",JSONArray(profiles.profileSwitchHistory.first().filter { it.timestampMillis >= current!!.getLong("startedAtMs") }.map {
                    JSONObject().put("profile",it.profileName).put("trigger",it.trigger).put("timeMs",it.timestampMillis)
                }))
            save()
        }
    }
    private suspend fun await(message: String, timeout: Long = 12_000, predicate: suspend () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate() && SystemClock.uptimeMillis() < deadline) delay(100)
        assertTrue(message, predicate())
    }
    private fun windowTitles(): List<String> {
        var titles = emptyList<String>()
        instrumentation.runOnMainSync { titles = WindowInspector.getGlobalWindowViews().mapNotNull { (it.layoutParams as? WindowManager.LayoutParams)?.title?.toString() } }
        return titles
    }
    private fun wake() { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
    private fun clearFixtures() { for (letter in "abcd") shell("am force-stop ${pkg(letter)}") }
    @Suppress("DEPRECATION") private fun running(type: Class<*>) = context.getSystemService(ActivityManager::class.java).getRunningServices(100).any { it.service.className == type.name }
    private suspend fun stopServices() {
        for (type in listOf(OverlayHostService::class.java,SleepProfileMonitorService::class.java,AppDetectionService::class.java)) context.stopService(Intent(context,type))
        await("services stopped") { !running(AppDetectionService::class.java) && !running(SleepProfileMonitorService::class.java) && !running(OverlayHostService::class.java) }
    }
    private fun save() { File(context.filesDir,"letter-apps-$method.json").writeText(report.toString(2)) }

    private inner class Backup {
        lateinit var savedSettings: AppSettings
        private lateinit var savedProfiles: List<PerformanceProfile>
        private lateinit var deleted: Set<String>
        private lateinit var order: List<String>
        private lateinit var assignments: List<AppProfileAssignment>
        private lateinit var history: List<ProfileSwitchHistoryEntry>
        private lateinit var values: Map<Int,Int>
        private var gpu: Int? = null
        private var applied: String? = null
        private var selected: String? = null
        private var effective: EffectiveProfileState? = null
        private lateinit var sleepValues: Map<Int,Int>
        private var sleepGpu: Int? = null
        private var sleepKnown = false
        private var sleepId: String? = null
        private var sleepEffective: EffectiveProfileState? = null
        var detectorRunning = false
        var sleepRunning = false
        suspend fun capture() {
            savedSettings=settings.settings.first(); savedProfiles=profiles.profiles.first(); deleted=profiles.deletedBundledProfileIds.first()
            order=profiles.displayOrder.first(); assignments=profiles.appProfileAssignments.first(); history=profiles.profileSwitchHistory.first()
            values=profiles.lastValues.first(); gpu=profiles.lastGpuValue.first(); applied=profiles.lastAppliedDisplayProfileId.first()
            selected=profiles.selectedProfileId.first(); effective=profiles.effectiveProfileState.first()
            sleepValues=profiles.sleepRestoreValues.first(); sleepGpu=profiles.sleepRestoreGpuValue.first(); sleepKnown=profiles.sleepRestoreGpuKnown.first()
            sleepId=profiles.sleepRestoreDisplayProfileId.first(); sleepEffective=profiles.sleepRestoreEffectiveProfileState.first()
            detectorRunning=running(AppDetectionService::class.java); sleepRunning=running(SleepProfileMonitorService::class.java)
        }
        suspend fun restore() {
            profiles.resetProfiles(); profiles.replaceProfiles(savedProfiles)
            deleted.forEach { profiles.markBundledProfileDeleted(it) }; profiles.persistDisplayOrder(order)
            profiles.appProfileAssignments.first().forEach { profiles.deleteAppProfileAssignment(it.packageName) }
            assignments.forEach { profiles.saveAppProfileAssignment(it) }
            profiles.persistNormalProfileState(values,applied,gpu,selected)
            effective?.let { profiles.persistEffectiveProfileState(it) } ?: profiles.clearEffectiveProfileState()
            profiles.clearSleepRestoreState()
            if(sleepValues.isNotEmpty()) profiles.persistSleepRestoreState(sleepValues,sleepId,sleepGpu,sleepKnown,sleepEffective)
            profiles.clearProfileSwitchHistory(); history.asReversed().forEach { profiles.appendProfileSwitchHistory(it,maxOf(history.size,savedSettings.profileSwitchHistoryLimit)) }
            with(savedSettings) {
                settings.persistAutoTuneEnabled(autoTuneEnabled); settings.persistSleepProfile(sleepProfileEnabled,sleepProfileId)
                settings.persistPrivilegedExecutionMethodId(privilegedExecutionMethodId)
                settings.persistLeftEdgeProfilePickerEnabled(leftEdgeProfilePickerEnabled)
                settings.persistProfileSwitchToastsEnabled(profileSwitchToastsEnabled); settings.persistProfileSwitchHistoryLimit(profileSwitchHistoryLimit)
                settings.persistEdgeHandleHeightDp(edgeHandleHeightDp); settings.persistEdgeHandleThicknessDp(edgeHandleThicknessDp)
                settings.persistEdgeHandleVerticalPositionPercent(edgeHandleVerticalPositionPercent); settings.persistEdgeHandleOpacityPercent(edgeHandleOpacityPercent)
                settings.persistCustomAccentColor(customAccentColor); settings.persistPresetAccentColor(accentColor); settings.persistColorSource(colorSource)
                settings.persistDisplayFrequenciesAsPercent(displayFrequenciesAsPercent)
            }
        }
    }
}
