package com.aure.clustertune.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aure.clustertune.ui.designsystem.component.CtNumericField
import com.aure.clustertune.ui.designsystem.component.CtSlider
import com.aure.clustertune.ui.designsystem.component.CtSwitch
import com.aure.clustertune.ui.designsystem.component.CtSwitchPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesignSystemComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchPreference_exposesOneToggle_andRowClickTogglesIt() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtSwitchPreference(
                    title = { Text("Enabled") },
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        composeRule.onAllNodes(hasSwitchRole).assertCountEquals(1)
        composeRule.onNode(hasSwitchRole)
            .assertIsOff()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.runOnIdle { assertTrue(checked) }
    }

    @Test
    fun switch_exposesToggleState_andInvokesCallback() {
        var checked by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtSwitch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        composeRule.onNode(hasSwitchRole).assertIsOff().performClick()
        composeRule.runOnIdle { assertTrue(checked) }
    }

    @Test
    fun numericField_filtersNonDigits_andRetainsCompactTouchHeight() {
        var value by mutableStateOf("")
        composeRule.setContent {
            MaterialTheme {
                CtNumericField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier,
                    containerHeight = 40.dp,
                )
            }
        }

        composeRule.onNode(hasSetTextAction())
            .assertHeightIsAtLeast(48.dp)
            .performTextInput("a1b2")
        composeRule.runOnIdle { assertEquals("12", value) }
    }

    @Test
    fun slider_exposesProgressSemantics_andRetainsInteractionHeight() {
        var value by mutableStateOf(0.5f)
        composeRule.setContent {
            MaterialTheme {
                CtSlider(value = value, onValueChange = { value = it })
            }
        }

        composeRule.onNode(hasProgressRangeInfo)
            .assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
                setProgress(0.75f)
            }
        composeRule.runOnIdle { assertEquals(0.75f, value, 0.001f) }
    }

    private companion object {
        val hasProgressRangeInfo = SemanticsMatcher("has progress range semantics") { node ->
            node.config.contains(SemanticsProperties.ProgressBarRangeInfo)
        }
        val hasSwitchRole = SemanticsMatcher("has switch role") { node ->
            node.config.contains(SemanticsProperties.Role) &&
                node.config[SemanticsProperties.Role] == Role.Switch
        }
    }
}
