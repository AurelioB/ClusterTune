package com.aure.clustertune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import com.aure.clustertune.model.AppSettings
import com.aure.clustertune.model.TileInteractionBehavior
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertValueEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PerformanceHudOverlayComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun hudTileOptionFollowsExperimentalSetting() {
        val settings = mutableStateOf(AppSettings(tileTapBehavior = TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD))
        composeRule.setContent {
            MaterialTheme {
                TileBehaviorSelector(
                    selected = settings.value.effectiveTileTapBehavior,
                    autoTuneEnabled = settings.value.autoTuneEnabled,
                    onChange = {},
                )
            }
        }
        composeRule.onNodeWithText("Performance HUD").assertDoesNotExist()
        composeRule.onNodeWithText("Quick tuner").assertIsDisplayed()
        composeRule.runOnIdle { settings.value = settings.value.copy(autoTuneEnabled = true) }
        composeRule.onNodeWithText("Performance HUD").assertIsDisplayed()
        composeRule.runOnIdle { settings.value = settings.value.copy(autoTuneEnabled = false) }
        composeRule.onNodeWithText("Performance HUD").assertDoesNotExist()
    }

    @Test
    fun fixedProfileRendersAvailableRows_withoutInteractiveControl() {
        composeRule.setContent {
            PerformanceHudOverlay(
                model = PerformanceHudUiModel(
                    framesPerSecond = 59.94,
                    p95FrameTimeMillis = 18.24,
                    frameRateHistoryFps = listOf(54f, 57f, null, 59.94f),
                    frameRateGraphMaximumFps = 60f,
                    cpuLoadPercent = 47.6,
                    cpuClockKHz = 2_850_000L,
                    cpuLoadHistoryPercent = listOf(25f, 50f, null, 47.6f),
                    gpuBusyPercent = 81.0,
                    gpuClockHz = 680_000_000L,
                    gpuBusyHistoryPercent = listOf(35f, 60f, 81f),
                    maxTemperatureCelsius = 71.6,
                    oemPerformanceProfile = "Performance",
                    fanProfile = "Turbo",
                    tuneMode = PerformanceHudTuneMode.FixedProfile("Balanced"),
                ),
                onAutoTuneTargetCommit = { _, _, _ -> },
            )
        }

        composeRule.onNodeWithTag(PerformanceHudTestTags.OVERLAY).assertIsDisplayed()
        composeRule.onNodeWithText("59.9 FPS · P95 18.2 ms").assertIsDisplayed()
        composeRule.onNodeWithText("48% · 2.85 GHz").assertIsDisplayed()
        composeRule.onNodeWithText("81% · 680 MHz").assertIsDisplayed()
        composeRule.onNodeWithText("72°C").assertIsDisplayed()
        composeRule.onNodeWithText("Balanced").assertIsDisplayed()
        composeRule.onNodeWithTag(PerformanceHudTestTags.FRAME_GRAPH)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(72.dp)
            .assertHeightIsEqualTo(16.dp)
            .assertContentDescriptionEquals("FPS history")
        composeRule.onNodeWithTag(PerformanceHudTestTags.CPU_GRAPH)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("CPU usage history")
        composeRule.onNodeWithTag(PerformanceHudTestTags.GPU_GRAPH)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("GPU usage history")
        composeRule.onAllNodesWithTag(PerformanceHudTestTags.WAITING).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PerformanceHudTestTags.AUTO_TUNE_SLIDER).assertCountEquals(0)
    }

    @Test
    fun emptyTelemetryShowsWaiting_whileKeepingContext() {
        composeRule.setContent {
            PerformanceHudOverlay(
                model = PerformanceHudUiModel(
                    oemPerformanceProfile = "Performance",
                    tuneMode = PerformanceHudTuneMode.FixedProfile("Balanced"),
                ),
                onAutoTuneTargetCommit = { _, _, _ -> },
            )
        }

        composeRule.onNodeWithTag(PerformanceHudTestTags.WAITING).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for telemetry").assertIsDisplayed()
        composeRule.onNodeWithText("Performance").assertIsDisplayed()
        composeRule.onNodeWithText("Balanced").assertIsDisplayed()
    }

    @Test
    fun autoTuneSliderCommitsOnlyAfterGestureFinishes() {
        val commits = mutableListOf<Triple<String, Int, Int>>()
        composeRule.setContent {
            PerformanceHudOverlay(
                model = PerformanceHudUiModel(
                    framesPerSecond = 58.0,
                    p95FrameTimeMillis = 17.4,
                    frameRateHistoryFps = listOf(52f, 55f, 58f),
                    frameRateGraphMaximumFps = 60f,
                    cpuLoadPercent = 55.0,
                    cpuClockKHz = 1_840_000L,
                    cpuLoadHistoryPercent = listOf(40f, 50f, 55f),
                    gpuBusyPercent = 72.0,
                    gpuClockHz = 401_000_000L,
                    gpuBusyHistoryPercent = listOf(60f, 66f, 72f),
                    maxTemperatureCelsius = 46.0,
                    oemPerformanceProfile = "Standard",
                    fanProfile = "Smart",
                    tuneMode = PerformanceHudTuneMode.AutoTune(
                        packageName = "com.example.game",
                        targetFps = 45,
                        targetRange = 30..60,
                        configuredTargetFps = 120,
                    ),
                ),
                onAutoTuneTargetCommit = { packageName, expected, target ->
                    commits += Triple(packageName, expected, target)
                },
            )
        }

        composeRule.onNodeWithTag(PerformanceHudTestTags.AUTO_TUNE_SLIDER)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(72.dp)
            .assertHeightIsEqualTo(16.dp)
            .assertContentDescriptionEquals("Auto Tune FPS target")
            .assertValueEquals("45 FPS")
            .assertRangeInfoEquals(ProgressBarRangeInfo(45f, 30f..60f, 29))
            .performTouchInput {
                swipe(
                    start = center,
                    end = centerRight,
                    durationMillis = 300,
                )
            }

        composeRule.onNodeWithTag(PerformanceHudTestTags.CPU_GRAPH).assertIsDisplayed()
        composeRule.onNodeWithTag(PerformanceHudTestTags.GPU_GRAPH).assertIsDisplayed()
        composeRule.onNodeWithTag(PerformanceHudTestTags.FRAME_GRAPH).assertIsDisplayed()
        composeRule.onNodeWithText("58.0 FPS · P95 17.4 ms").assertIsDisplayed()
        composeRule.onNodeWithText("55% · 1.84 GHz").assertIsDisplayed()
        composeRule.onNodeWithText("72% · 401 MHz").assertIsDisplayed()
        composeRule.onNodeWithText("46°C").assertIsDisplayed()
        composeRule.onNodeWithText("Standard").assertIsDisplayed()
        composeRule.onNodeWithText("Smart").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals("com.example.game", commits.single().first)
            assertEquals(120, commits.single().second)
            assertTrue(commits.single().third in 46..60)
        }
        composeRule.onNodeWithText("45 FPS").assertIsDisplayed()
    }

    @Test
    fun autoTuneSliderAccessibilityProgressCommitsRoundedFps() {
        val commits = mutableListOf<Triple<String, Int, Int>>()
        composeRule.setContent {
            PerformanceHudOverlay(
                model = PerformanceHudUiModel(
                    tuneMode = PerformanceHudTuneMode.AutoTune(
                        packageName = "com.example.game",
                        targetFps = 45,
                        targetRange = 30..60,
                        configuredTargetFps = 120,
                    ),
                ),
                onAutoTuneTargetCommit = { packageName, expected, target ->
                    commits += Triple(packageName, expected, target)
                },
            )
        }

        composeRule.onNodeWithTag(PerformanceHudTestTags.AUTO_TUNE_SLIDER)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                assertTrue(setProgress(47.6f))
            }

        composeRule.runOnIdle {
            assertEquals(listOf(Triple("com.example.game", 120, 48)), commits)
        }
    }

    @Test
    fun unsupportedGraphHistoryIsOmitted() {
        composeRule.setContent {
            PerformanceHudOverlay(
                model = PerformanceHudUiModel(
                    framesPerSecond = 42.0,
                    frameRateHistoryFps = listOf(null, Float.NaN, -1f),
                    frameRateGraphMaximumFps = 60f,
                    cpuLoadPercent = 42.0,
                    cpuLoadHistoryPercent = listOf(null, Float.NaN, -1f),
                    gpuClockHz = 680_000_000L,
                ),
                onAutoTuneTargetCommit = { _, _, _ -> },
            )
        }

        composeRule.onNodeWithTag(PerformanceHudTestTags.CPU_ROW).assertIsDisplayed()
        composeRule.onNodeWithTag(PerformanceHudTestTags.GPU_ROW).assertIsDisplayed()
        composeRule.onAllNodesWithTag(PerformanceHudTestTags.FRAME_GRAPH).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PerformanceHudTestTags.CPU_GRAPH).assertCountEquals(0)
        composeRule.onAllNodesWithTag(PerformanceHudTestTags.GPU_GRAPH).assertCountEquals(0)
    }
}
