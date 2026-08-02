package com.aure.clustertune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aure.clustertune.permissions.AppAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionCheckDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialog_rendersOnlySuppliedAccess_withPurposeAndExactInstructions() {
        composeRule.setContent {
            MaterialTheme {
                PermissionCheckDialog(
                    missingAccess = listOf(AppAccess.OVERLAY, AppAccess.NOTIFICATIONS),
                    onFixAccess = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Overlay access").assertIsDisplayed()
        composeRule.onNodeWithText("Shows Quick Settings controls and the edge picker over games.").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings, then allow ClusterTune to display over other apps.").assertIsDisplayed()
        composeRule.onNodeWithText("Notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Shows background status for overlays and automation while they run.").assertIsDisplayed()
        composeRule.onNodeWithText("Open settings, then allow notifications for ClusterTune.").assertIsDisplayed()

        assertTrue(composeRule.onAllNodesWithText("Usage access").fetchSemanticsNodes().isEmpty())
        assertTrue(
            composeRule.onAllNodesWithText("Identifies the foreground game for app profiles and profile pickers.")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    @Test
    fun fixButtons_returnTheirCorrespondingAccess_inStableOrder() {
        val fixed = mutableListOf<AppAccess>()
        composeRule.setContent {
            MaterialTheme {
                PermissionCheckDialog(
                    missingAccess = listOf(AppAccess.OVERLAY, AppAccess.USAGE, AppAccess.NOTIFICATIONS),
                    onFixAccess = { fixed += it },
                    onDismiss = {},
                )
            }
        }

        val expected = listOf(AppAccess.OVERLAY, AppAccess.USAGE, AppAccess.NOTIFICATIONS)
        composeRule.onAllNodesWithText("Fix").assertCountEquals(expected.size)
        expected.forEachIndexed { index, access ->
            composeRule.onAllNodesWithText("Fix")[index].performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(access, fixed.last()) }
        }
    }

    @Test
    fun notNow_dismissesDialog() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                PermissionCheckDialog(
                    missingAccess = listOf(AppAccess.USAGE),
                    onFixAccess = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Not now").performClick()
        composeRule.runOnIdle { assertEquals(true, dismissed) }
    }
}
