package com.aure.clustertune.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aure.clustertune.ui.designsystem.component.CtAppIdentity
import com.aure.clustertune.ui.designsystem.component.CtDashedCard
import com.aure.clustertune.ui.designsystem.component.CtDivider
import com.aure.clustertune.ui.designsystem.component.CtIcon
import com.aure.clustertune.ui.designsystem.component.CtSelectableRow
import com.aure.clustertune.ui.designsystem.component.CtSectionCard
import com.aure.clustertune.ui.designsystem.component.CtSelectionIndicator
import com.aure.clustertune.ui.designsystem.component.CtPreferenceRow
import com.aure.clustertune.ui.designsystem.component.CtRowSurface
import com.aure.clustertune.ui.designsystem.component.CtStatePanel
import com.aure.clustertune.ui.designsystem.component.CtStatePanelState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LeafComponentsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun selectableRow_hasRadioSemanticsAndTarget() {
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtSelectableRow(title = { Text("Choice") }, selected = selected, onClick = { selected = true })
            }
        }
        composeRule.onNode(hasRadioRole).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertTrue(selected) }
    }

    @Test
    fun selectableRow_exposesSelectedState() {
        composeRule.setContent {
            MaterialTheme { CtSelectableRow(title = { Text("Choice") }, selected = true, onClick = {}) }
        }
        composeRule.onNode(hasRadioRole).assertIsSelected()
    }

    @Test
    fun statePanel_rendersCallerTitle() {
        composeRule.setContent {
            MaterialTheme { CtStatePanel(CtStatePanelState.Loading, title = { Text("Loading") }) }
        }
        composeRule.onNode(hasText("Loading")).assertExists()
    }

    @Test
    fun statePanel_rendersMessageAndActionForErrorState() {
        composeRule.setContent {
            MaterialTheme {
                CtStatePanel(
                    state = CtStatePanelState.Error,
                    title = { Text("Could not connect") },
                    message = { Text("Try again") },
                    action = { Text("Retry") },
                )
            }
        }
        composeRule.onNode(hasText("Could not connect")).assertExists()
        composeRule.onNode(hasText("Try again")).assertExists()
        composeRule.onNode(hasText("Retry")).assertExists()
    }

    @Test
    fun preferenceRow_disabledStateIsExposed() {
        composeRule.setContent {
            MaterialTheme {
                CtPreferenceRow(title = { Text("Disabled") }, enabled = false, onClick = {})
            }
        }
        composeRule.onNodeWithText("Disabled").assertIsNotEnabled()
    }

    @Test
    fun rowSurface_selectedStateIsExposed() {
        composeRule.setContent {
            MaterialTheme {
                CtRowSurface(selected = true, onClick = {}) { Text("Selected") }
            }
        }
        composeRule.onNodeWithText("Selected").assertIsEnabled().assertIsSelected()
    }

    @Test
    fun appIdentity_rendersCallerOwnedLabelAndSubtitle() {
        composeRule.setContent {
            MaterialTheme {
                CtAppIdentity(label = "Cocoon", subtitle = "com.example.cocoon")
            }
        }

        composeRule.onNodeWithText("Cocoon").assertExists()
        composeRule.onNodeWithText("com.example.cocoon").assertExists()
    }

    @Test
    fun sectionAndDashedCards_renderSlottedContent() {
        composeRule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    CtSectionCard(title = { Text("Section") }) { Text("Setting") }
                    CtDashedCard { Text("Empty state") }
                }
            }
        }

        composeRule.onNodeWithText("Section").assertExists()
        composeRule.onNodeWithText("Setting").assertExists()
        composeRule.onNodeWithText("Empty state").assertExists()
    }

    @Test
    fun selectionIndicator_exposesOneRadioActionAndTarget() {
        var selected by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                CtSelectionIndicator(
                    selected = selected,
                    onClick = { selected = true },
                    contentDescription = "Choose profile",
                )
            }
        }

        composeRule.onNode(hasRadioRole).assertHeightIsAtLeast(48.dp).performClick()
        composeRule.runOnIdle { assertTrue(selected) }
    }

    @Test
    fun divider_acceptsCallerModifier() {
        composeRule.setContent {
            MaterialTheme { CtDivider(Modifier.testTag("divider")) }
        }

        composeRule.onNodeWithTag("divider").assertExists()
    }

    @Test
    fun icon_exposesCallerOwnedDescription() {
        composeRule.setContent {
            MaterialTheme {
                CtIcon(symbol = "settings", contentDescription = "Open settings")
            }
        }

        composeRule.onNodeWithContentDescription("Open settings").assertExists()
    }

    private companion object {
        val hasRadioRole = SemanticsMatcher("radio role") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Role) &&
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Role] == Role.RadioButton
        }
    }
}
