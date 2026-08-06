package com.aure.clustertune.ui.designsystem.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactSettingsFieldTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun numericField_keepsValueVisibleAtCompactHeight_andFiltersDigits() {
        var value by mutableStateOf("12")
        composeRule.setContent {
            MaterialTheme {
                CtNumericField(
                    value = value,
                    onValueChange = { value = numericDigits(it, maxDigits = 3) },
                    containerHeight = 48.dp,
                    label = { androidx.compose.material3.Text("Every days") },
                    maxDigits = 3,
                    modifier = Modifier.testTag("numeric-field"),
                )
            }
        }

        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("Every days").assertIsDisplayed()
        composeRule.onNodeWithTag("numeric-field").assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag("numeric-field").performTextReplacement("a1234")
        composeRule.runOnIdle { assertEquals("123", value) }
    }

    @Test
    fun compactDropdownField_keepsWhileAsleepTextVisibleAtCompactHeight() {
        composeRule.setContent {
            MaterialTheme {
                CtCompactOutlinedField(
                    value = "While asleep",
                    onValueChange = {},
                    readOnly = true,
                    containerHeight = 48.dp,
                    modifier = Modifier.testTag("sleep-dropdown"),
                )
            }
        }

        composeRule.onNodeWithText("While asleep").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep-dropdown").assertHeightIsEqualTo(48.dp)
    }
}
