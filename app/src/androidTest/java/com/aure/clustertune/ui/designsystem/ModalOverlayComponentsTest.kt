package com.aure.clustertune.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import com.aure.clustertune.ui.designsystem.component.CtConfirmationDialog
import com.aure.clustertune.ui.designsystem.component.CtModalScaffold
import com.aure.clustertune.ui.designsystem.component.CtOverlayFrame
import com.aure.clustertune.ui.designsystem.component.CtOverlayFrameTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModalOverlayComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun overlayFrame_keepsPanelContentVisible() {
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(onDismissRequest = {}) { Text("Panel") }
            }
        }

        composeRule.onNodeWithText("Panel").assertIsDisplayed()
    }

    @Test
    fun overlayFrame_dismissesOutsideButIsolatesPanelTaps() {
        var dismissCount by mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(
                    onDismissRequest = { dismissCount += 1 },
                    widthFraction = 0.5f,
                    heightFraction = 0.5f,
                ) { Text("Panel") }
            }
        }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Panel)
            .performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(0, dismissCount) }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Scrim)
            .performTouchInput { click(Offset(2f, 2f)) }
        composeRule.runOnIdle { assertEquals(1, dismissCount) }
    }

    @Test
    fun overlayFrame_resolvesFractionAgainstAvailableWidthBeforeMaximum() {
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(
                    onDismissRequest = {},
                    modifier = Modifier.size(width = 240.dp, height = 200.dp),
                    maxWidth = 200.dp,
                    widthFraction = 0.5f,
                    heightFraction = 0.5f,
                ) { Text("Panel") }
            }
        }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Panel)
            .assertWidthIsEqualTo(120.dp)
            .assertHeightIsEqualTo(100.dp)
    }

    @Test
    fun overlayFrame_capsFractionalHeightAtMaximum() {
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(
                    onDismissRequest = {},
                    modifier = Modifier.size(width = 400.dp, height = 300.dp),
                    heightFraction = 0.75f,
                    maxHeight = 120.dp,
                ) { Text("Panel") }
            }
        }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Panel)
            .assertHeightIsEqualTo(120.dp)
    }

    @Test
    fun overlayFrame_wrapsContentWhenNoHeightFractionIsGiven() {
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(
                    onDismissRequest = {},
                    modifier = Modifier.size(width = 400.dp, height = 300.dp),
                    heightFraction = null,
                    maxHeight = 120.dp,
                ) { Box(Modifier.height(80.dp)) }
            }
        }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Panel)
            .assertHeightIsEqualTo(80.dp)
    }

    @Test
    fun overlayFrame_canIgnoreOutsideTaps() {
        var dismissCount by mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                CtOverlayFrame(
                    onDismissRequest = { dismissCount += 1 },
                    dismissOnClickOutside = false,
                ) { Text("Panel") }
            }
        }

        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Scrim)
            .performTouchInput { click(Offset(2f, 2f)) }
        composeRule.runOnIdle { assertEquals(0, dismissCount) }
    }

    @Test
    fun confirmationDialog_confirmInvokesCallback() {
        var confirmed by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtConfirmationDialog(
                    title = "Confirm",
                    message = "Proceed?",
                    confirmLabel = "Proceed",
                    dismissLabel = "Cancel",
                    onConfirm = { confirmed = true },
                    onDismissRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("Proceed").performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }

    @Test
    fun confirmationDialog_dismissButtonInvokesCallback() {
        var dismissed by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtConfirmationDialog(
                    title = "Confirm",
                    message = "Proceed?",
                    confirmLabel = "Proceed",
                    dismissLabel = "Cancel",
                    onConfirm = {},
                    onDismissRequest = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun modalScaffold_rendersHeaderBodyAndFooterSlots() {
        composeRule.setContent {
            MaterialTheme {
                CtModalScaffold(
                    title = { Text("Header") },
                    footer = { Text("Footer") },
                ) {
                    Text("Body")
                }
            }
        }

        composeRule.onNodeWithText("Header").assertIsDisplayed()
        composeRule.onNodeWithText("Body").assertIsDisplayed()
        composeRule.onNodeWithText("Footer").assertIsDisplayed()
    }
}
