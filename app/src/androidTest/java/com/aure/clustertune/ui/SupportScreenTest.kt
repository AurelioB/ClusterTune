package com.aure.clustertune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun supportContent_andDoneAction_areDisplayed() {
        var doneCount by mutableIntStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                SupportScreen(onBack = { doneCount++ })
            }
        }

        composeRule.onNodeWithText("Support ClusterTune").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ko-fi donation QR code").assertIsDisplayed()
        composeRule.onNodeWithText(
            "ClusterTune is built and maintained independently. If it helps you tune your device, consider supporting my work on Ko-fi.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("https://ko-fi.com/J3J518XVKR").assertIsDisplayed()
        composeRule.onNodeWithText("Open Ko-fi").assertIsDisplayed()

        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertEquals(1, doneCount) }
    }

    @Test
    fun headerTitle_andDone_shareVerticalCenter() {
        composeRule.setContent {
            MaterialTheme {
                SupportScreen(onBack = {})
            }
        }

        val titleBounds = composeRule.onNodeWithText("Support ClusterTune")
            .fetchSemanticsNode().boundsInRoot
        val doneBounds = composeRule.onNodeWithText("Done")
            .fetchSemanticsNode().boundsInRoot
        assertTrue("header title and Done should share a row", kotlin.math.abs(titleBounds.center.y - doneBounds.center.y) <= 2f)
    }
}
