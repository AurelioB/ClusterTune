package com.aure.clustertune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutoTuneRuntimeStatusCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cardDisplaysRuntimeSummary() {
        composeRule.setContent {
            MaterialTheme {
                AutoTuneRuntimeStatusCard(
                    presentation = AutoTuneRuntimePresentation(
                        appAndTarget = "Example Game · Target 60 FPS",
                        status = "Recovering",
                        frameMetrics = "57.3 FPS · P95 20.6 ms",
                        utilization = "CPU C0 41% · CPU C6 82% · GPU 67%",
                        message = "Raising a CPU ceiling",
                        frameBackend = "surfaceflinger-latency",
                        active = true,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Auto Tune").assertIsDisplayed()
        composeRule.onNodeWithText("Recovering").assertIsDisplayed()
        composeRule.onNodeWithText("Example Game · Target 60 FPS").assertIsDisplayed()
        composeRule.onNodeWithText("57.3 FPS · P95 20.6 ms").assertIsDisplayed()
        composeRule.onNodeWithText("CPU C0 41% · CPU C6 82% · GPU 67%").assertIsDisplayed()
        composeRule.onNodeWithText("Raising a CPU ceiling").assertIsDisplayed()
        composeRule.onNodeWithText("Frames · surfaceflinger-latency").assertIsDisplayed()
    }
}
