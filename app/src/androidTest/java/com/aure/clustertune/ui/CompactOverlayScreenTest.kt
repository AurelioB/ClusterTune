package com.aure.clustertune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileSource
import com.aure.clustertune.model.TunerState
import com.aure.clustertune.ui.designsystem.component.CtCompactOverlayFrame
import com.aure.clustertune.ui.designsystem.component.CtOverlayFrameTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactOverlayScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appPickerFrame_usesSharedOverlayHostGeometry() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    CtCompactOverlayFrame(onDismissRequest = {}) {
                        androidx.compose.material3.Text("Apps")
                    }
                }
            }
        }

        // The shared wrapper applies 12dp horizontal padding on each side.
        composeRule.onNodeWithTag(CtOverlayFrameTestTags.Panel)
            .assertWidthIsEqualTo(276.dp)
            .assertIsDisplayed()
    }

    @Test
    fun sharedHeader_isPresentInBothModes_andModeButtonSwitchesContent() {
        var mode by mutableStateOf(CompactOverlayMode.PROFILES)
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(),
                    displayFrequenciesAsPercent = false,
                    mode = mode,
                    onModeChange = { mode = it },
                    onApplyProfile = { _, _ -> },
                    onApplyCurrent = { _, _, _, _ -> },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> },
                )
            }
        }

        assertSharedHeader()
        composeRule.onNodeWithText("Small").assertExists()
        composeRule.onNodeWithContentDescription("Tuner").performClick()
        composeRule.runOnIdle { assertEquals(CompactOverlayMode.TUNER, mode) }
        assertSharedHeader()
        composeRule.onNodeWithText("Apply").assertExists()
    }

    @Test
    fun tunerPresetSelection_isStagedUntilApply_andCancelOnlyDismisses() {
        var applyCount = 0
        var assignmentCount = 0
        var dismissCount = 0
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(),
                    displayFrequenciesAsPercent = false,
                    mode = CompactOverlayMode.TUNER,
                    onModeChange = {},
                    onApplyProfile = { _, _ -> applyCount++ },
                    onApplyCurrent = { _, _, _, _ -> applyCount++ },
                    onDismissRequest = { dismissCount++ },
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> assignmentCount++ },
                )
            }
        }

        composeRule.onNodeWithText("Small").performClick()
        composeRule.runOnIdle {
            assertEquals(0, applyCount)
            assertEquals(0, assignmentCount)
        }
        composeRule.onNodeWithText("Apply").performClick()
        composeRule.runOnIdle { assertEquals(1, applyCount) }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle {
            assertEquals(1, applyCount)
            assertEquals(1, dismissCount)
            assertEquals(0, assignmentCount)
        }
    }

    @Test
    fun profileSelection_appliesImmediately_andCarriesEnabledAppProfileState() {
        var appliedProfile: PerformanceProfile? = null
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(assignment = AppProfileAssignment("com.example.game", "Example game", profileId = "small")),
                    displayFrequenciesAsPercent = false,
                    mode = CompactOverlayMode.PROFILES,
                    onModeChange = {},
                    onApplyProfile = { profile, enabled ->
                        appliedProfile = profile
                        assertTrue(enabled)
                    },
                    onApplyCurrent = { _, _, _, _ -> },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Large").performClick()
        composeRule.runOnIdle {
            assertEquals("large", appliedProfile?.id)
        }
    }

    @Test
    fun customAssignment_isRepresentedAsCustomInTunerMode() {
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(
                        assignment = AppProfileAssignment(
                            packageName = "com.example.game",
                            appLabel = "Example game",
                            customMaxFrequencies = mapOf(0 to 1_200_000),
                        ),
                    ),
                    displayFrequenciesAsPercent = false,
                    mode = CompactOverlayMode.TUNER,
                    onModeChange = {},
                    onApplyProfile = { _, _ -> },
                    onApplyCurrent = { _, _, _, _ -> },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Custom").assertExists()
    }

    @Test
    fun tunerMode_stripsUnderclockSuffix_butProfilesModeKeepsName() {
        val underclock = PerformanceProfile("small", "Small Underclock", mapOf(0 to 500_000), ProfileSource.BUNDLED)
        val tunerState = state().copy(displayProfiles = listOf(underclock))
        var mode by mutableStateOf(CompactOverlayMode.PROFILES)
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = tunerState,
                    displayFrequenciesAsPercent = false,
                    mode = mode,
                    onModeChange = { mode = it },
                    onApplyProfile = { _, _ -> },
                    onApplyCurrent = { _, _, _, _ -> },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                )
            }
        }
        composeRule.onNodeWithText("Small Underclock").assertExists()
        composeRule.runOnIdle { mode = CompactOverlayMode.TUNER }
        composeRule.onNodeWithText("Small").assertExists()
        assertTrue(composeRule.onAllNodesWithText("Small Underclock").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun assignmentMode_hidesAppProfileToggle_butProfileSelectionStillApplies() {
        var appliedProfile: PerformanceProfile? = null
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(),
                    displayFrequenciesAsPercent = false,
                    mode = CompactOverlayMode.PROFILES,
                    onModeChange = {},
                    onApplyProfile = { profile, _ -> appliedProfile = profile },
                    onApplyCurrent = { _, _, _, _ -> },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> },
                    showAppProfileToggle = false,
                )
            }
        }

        assertTrue(composeRule.onAllNodesWithText("App profile").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Large").performClick()
        composeRule.runOnIdle { assertEquals("large", appliedProfile?.id) }
    }

    @Test
    fun savedCustomAssignment_applyUsesSnapshotWithoutNamedProfile() {
        var appliedNamedProfile: PerformanceProfile? = PerformanceProfile("sentinel", "Sentinel", emptyMap(), ProfileSource.USER)
        var appliedCustomValues: Map<Int, Int>? = null
        var appProfileEnabled = false
        val snapshot = mapOf(0 to 1_200_000)
        composeRule.setContent {
            MaterialTheme {
                CompactOverlayScreen(
                    state = state(
                        assignment = AppProfileAssignment(
                            packageName = "com.example.game",
                            appLabel = "Example game",
                            customMaxFrequencies = snapshot,
                        ),
                    ).copy(
                        activeDisplayProfileId = "large",
                        lastAppliedDisplayProfileId = "large",
                        selectedDisplayProfileId = "large",
                    ),
                    displayFrequenciesAsPercent = false,
                    mode = CompactOverlayMode.TUNER,
                    onModeChange = {},
                    onApplyProfile = { _, _ -> },
                    onApplyCurrent = { _, namedProfile, customValues, enabled ->
                        appliedNamedProfile = namedProfile
                        appliedCustomValues = customValues
                        appProfileEnabled = enabled
                    },
                    onDismissRequest = {},
                    onRefreshLiveValues = {},
                    contextPackageName = "com.example.game",
                    contextLabel = "Example game",
                    onAppProfileAssignmentChange = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.runOnIdle {
            assertEquals(null, appliedNamedProfile)
            assertEquals(snapshot, appliedCustomValues)
            assertTrue(appProfileEnabled)
        }
    }

    @Test
    fun matchingOffscreenProfile_isHighlightedAndScrolledIntoView() {
        val profiles = (0 until 12).map { index ->
            PerformanceProfile(
                id = "profile-$index",
                name = "Profile $index",
                maxFrequencies = mapOf(0 to (300_000 + index * 50_000)),
                source = ProfileSource.BUNDLED,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    CompactOverlayScreen(
                        state = state().copy(
                            currentValues = profiles.last().maxFrequencies,
                            displayProfiles = profiles,
                            selectedDisplayProfileId = profiles.last().id,
                        ),
                        displayFrequenciesAsPercent = false,
                        mode = CompactOverlayMode.TUNER,
                        onModeChange = {},
                        onApplyProfile = { _, _ -> },
                        onApplyCurrent = { _, _, _, _ -> },
                        onDismissRequest = {},
                        onRefreshLiveValues = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Profile 11").assertIsDisplayed()
    }

    @Test
    fun nonMatchingValues_selectCustomAndScrollCustomIntoView() {
        val profiles = (0 until 12).map { index ->
            PerformanceProfile(
                id = "profile-$index",
                name = "Profile $index",
                maxFrequencies = mapOf(0 to (300_000 + index * 50_000)),
                source = ProfileSource.BUNDLED,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(280.dp)) {
                    CompactOverlayScreen(
                        state = state().copy(
                            currentValues = mapOf(0 to 2_345_678),
                            displayProfiles = profiles,
                            selectedDisplayProfileId = null,
                            isManualSelection = true,
                        ),
                        displayFrequenciesAsPercent = false,
                        mode = CompactOverlayMode.TUNER,
                        onModeChange = {},
                        onApplyProfile = { _, _ -> },
                        onApplyCurrent = { _, _, _, _ -> },
                        onDismissRequest = {},
                        onRefreshLiveValues = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Custom").assertIsDisplayed()
    }

    private fun assertSharedHeader() {
        composeRule.onNodeWithText("Example game").assertExists()
        composeRule.onNodeWithText("com.example.game").assertExists()
        composeRule.onNodeWithText("App profile").assertExists()
        composeRule.onNodeWithContentDescription("Profiles").assertExists()
        composeRule.onNodeWithContentDescription("Tuner").assertExists()
        composeRule.onNodeWithContentDescription("Close").assertExists()
    }

    private fun state(assignment: AppProfileAssignment? = null): TunerState {
        val policies = listOf(
            CpuPolicyInfo(
                id = 0,
                policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
                scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
                currentMaxFreq = 1_000_000,
                selectableMaxFreq = 1_000_000,
                observedMaxFreq = 1_000_000,
                minFreq = 500_000,
                supportedFrequencies = listOf(500_000, 1_000_000, 1_500_000),
            ),
        )
        val small = PerformanceProfile("small", "Small", mapOf(0 to 500_000), ProfileSource.BUNDLED)
        val large = PerformanceProfile("large", "Large", mapOf(0 to 1_500_000), ProfileSource.BUNDLED)
        return TunerState(
            isLoading = false,
            isPServerAvailable = true,
            policies = policies,
            actualValues = mapOf(0 to 1_000_000),
            currentValues = mapOf(0 to 1_000_000),
            displayProfiles = listOf(small, large),
            appProfileAssignments = listOfNotNull(assignment),
        )
    }
}
