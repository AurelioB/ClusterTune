package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.VENDOR_GAME_ASSISTANT_PACKAGES
import com.aure.clustertune.apps.VisibleAppSnapshot
import com.aure.clustertune.apps.VisibleAppWindow
import com.aure.clustertune.apps.selectVisibleAppWindow
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.ui.CompactOverlayMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProfilePickerForegroundTest {
    @Test
    fun profilePicker_opensAutoTuneForForegroundAutoAssignment() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.AUTO_TUNE,
            initialCompactOverlayMode(CompactOverlayMode.PROFILES, "com.game", assignments),
        )
        assertEquals(
            CompactOverlayMode.TUNER,
            initialCompactOverlayMode(CompactOverlayMode.TUNER, "com.game", assignments),
        )
        assertEquals(
            CompactOverlayMode.PROFILES,
            initialCompactOverlayMode(CompactOverlayMode.PROFILES, "com.other", assignments),
        )
    }

    @Test
    fun correctedForegroundOpensAutoTuneUntilUserSelectsATab() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.AUTO_TUNE,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.PROFILES,
                currentMode = CompactOverlayMode.PROFILES,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = false,
            ),
        )
        assertEquals(
            CompactOverlayMode.TUNER,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.PROFILES,
                currentMode = CompactOverlayMode.TUNER,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = true,
            ),
        )
    }

    @Test
    fun requestedTunerModeIsNotAutomaticallyChangedForAutoAssignment() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.TUNER,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.TUNER,
                currentMode = CompactOverlayMode.TUNER,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = false,
            ),
        )
    }

    @Test
    fun profilesPickerWithoutForegroundUsesGlobalMode() {
        assertEquals(
            CompactOverlayMode.PROFILES,
            initialCompactOverlayMode(
                requestedMode = CompactOverlayMode.PROFILES,
                foregroundPackageName = null,
                assignments = emptyList(),
            ),
        )
    }

    private val ownPackage = "com.aure.clustertune"
    private val ignored = compactProfilePickerExcludedPackages(ownPackage)

    private fun app(packageName: String, label: String = packageName) =
        ForegroundAppInfo(packageName, label)

    private fun updateFromSnapshot(
        current: ForegroundAppInfo?,
        snapshot: VisibleAppSnapshot,
        pickerShowing: Boolean = true,
    ): ForegroundAppInfo? {
        val observation = observeCompactProfilePickerSnapshot(
            snapshot = snapshot,
            targetDisplayId = 0,
            excludedPackages = ignored,
            pickerShowing = pickerShowing,
            preferredPackageName = current?.packageName,
        )
        val detected = observation.packageName?.let(::app)
        return updateCompactProfilePickerForeground(
            current = current,
            detected = detected,
            ignoredPackages = ignored,
            hasFilteredVisibleWindow = observation.isObscured,
        )
    }

    private fun snapshotOf(vararg windows: VisibleAppWindow) = VisibleAppSnapshot(
        windowsByDisplay = mapOf(0 to windows.toList()),
        isInteractive = true,
        refreshRateFpsByDisplay = mapOf(0 to 60),
    )

    private fun emptyAccessibilitySnapshot(
        isInteractive: Boolean = true,
        displayExists: Boolean = true,
        pickerPackage: String? = null,
    ) = VisibleAppSnapshot(
        windowsByDisplay = emptyMap(),
        isInteractive = isInteractive,
        refreshRateFpsByDisplay = if (displayExists) mapOf(0 to 60) else emptyMap(),
        pickerPackageByDisplay = pickerPackage?.let { mapOf(0 to it) }.orEmpty(),
    )

    @Test
    fun unresolvedWithoutFilteredWindowClearsEstablishedContext() {
        assertNull(
            updateCompactProfilePickerForeground(
                current = app("com.game"),
                detected = null,
                ignoredPackages = ignored,
            ),
        )
    }

    @Test
    fun foregroundTargetLossClearsContextAndAllowsGlobalMutation() = runTest {
        val lost = updateCompactProfilePickerForeground(
            current = app("com.game"),
            detected = null,
            ignoredPackages = ignored,
        )
        var mutationCount = 0

        val ran = runCompactProfilePickerMutationIfCurrent(
            capturedForeground = lost,
            ensureCurrent = { false },
        ) {
            mutationCount++
        }

        assertNull(lost)
        assertTrue(ran)
        assertEquals(1, mutationCount)
    }

    @Test
    fun systemUiOnlyLaunchKeepsTheImmediatePickerInGlobalContext() {
        val snapshot = snapshotOf(
            VisibleAppWindow(SYSTEM_UI_PACKAGE, 0, isFocused = true, isActive = true),
        )
        val observation = observeCompactProfilePickerSnapshot(
            snapshot = snapshot,
            targetDisplayId = 0,
            excludedPackages = ignored,
            pickerShowing = false,
        )

        assertNull(observation.targetSnapshot)
        assertNull(updateFromSnapshot(current = null, snapshot = snapshot, pickerShowing = false))
    }

    @Test
    fun initialResolverFilteredNullLeavesContextEmpty() {
        val snapshot = snapshotOf(
            VisibleAppWindow(ownPackage, 0, isFocused = true),
        )

        assertNull(updateFromSnapshot(current = null, snapshot = snapshot))
    }

    @Test
    fun eventEvidenceSelectsGameWhenOemPublishesNoApplicationWindow() {
        val snapshot = emptyAccessibilitySnapshot(
            pickerPackage = "org.dolphinemu.dolphinemu",
        )
        val target = requireNotNull(
            compactProfilePickerTargetSnapshot(
                snapshot = snapshot,
                targetDisplayId = 0,
                excludedPackages = ignored,
            ),
        )
        val selected = requireNotNull(
            selectVisibleAppWindow(target, targetDisplayId = 0, excludedPackages = ignored),
        )

        assertTrue(snapshot.windowsByDisplay.isEmpty())
        assertTrue(snapshot.packages.isEmpty())
        assertEquals("org.dolphinemu.dolphinemu", selected.packageName)
        assertEquals(app(selected.packageName), updateFromSnapshot(null, snapshot))
    }

    @Test
    fun filteredSystemOwnAndAssistantWindowsRetainCurrentContext() {
        val established = app("com.game")
        val filteredPackages = listOf(
            SYSTEM_UI_PACKAGE,
            ownPackage,
            VENDOR_GAME_ASSISTANT_PACKAGES.first(),
        )

        filteredPackages.forEach { filteredPackage ->
            assertEquals(
                established,
                updateFromSnapshot(
                    current = established,
                    snapshot = snapshotOf(
                        VisibleAppWindow(filteredPackage, 0, isFocused = true),
                    ),
                ),
            )
        }
    }

    @Test
    fun newerEventEvidenceOverridesAStaleEnumeratedWindowForThePickerOnly() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("com.example.old", 0, isFocused = true)),
            ),
            isInteractive = true,
            refreshRateFpsByDisplay = mapOf(0 to 60),
            pickerPackageByDisplay = mapOf(0 to "com.example.new"),
        )

        val target = requireNotNull(
            compactProfilePickerTargetSnapshot(
                snapshot = snapshot,
                targetDisplayId = 0,
                excludedPackages = ignored,
            ),
        )
        val selected = requireNotNull(
            selectVisibleAppWindow(target, targetDisplayId = 0, excludedPackages = ignored),
        )

        assertEquals("com.example.new", selected.packageName)
        assertEquals(setOf("com.example.old"), snapshot.packages)
    }

    @Test
    fun emptySnapshotRetentionRequiresShowingInteractivePickerOnExistingDisplay() {
        val established = app("com.game")

        assertEquals(established, updateFromSnapshot(established, emptyAccessibilitySnapshot()))
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(),
                pickerShowing = false,
            ),
        )
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(isInteractive = false),
            ),
        )
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(displayExists = false),
            ),
        )
    }

    @Test
    fun resolverExclusionsContainTransientSystemOwnAndAssistantPackages() {
        assertTrue("android" in ignored)
        assertTrue(SYSTEM_UI_PACKAGE in ignored)
        assertTrue("com.android.permissioncontroller" in ignored)
        assertTrue("com.google.android.permissioncontroller" in ignored)
        assertTrue(ownPackage in ignored)
        assertTrue(VENDOR_GAME_ASSISTANT_PACKAGES.all(ignored::contains))
    }

    @Test
    fun realDifferentAppReplacesContextEvenWhileOwnWindowIsFiltered() {
        val snapshot = snapshotOf(
            VisibleAppWindow(ownPackage, 0, isFocused = true, isActive = true),
            VisibleAppWindow("com.android.launcher", 0, isActive = true),
        )
        val updated = updateFromSnapshot(app("com.game"), snapshot)

        assertEquals("com.android.launcher", updated?.packageName)
    }

    @Test
    fun staleCapturedPickerContextCannotMutateThePreviousApp() {
        assertTrue(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.game",
                capturedSessionToken = 7L,
                currentSessionToken = 7L,
            ),
        )
        assertFalse(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.android.launcher",
                capturedSessionToken = 7L,
                currentSessionToken = 7L,
            ),
        )
        assertFalse(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.game",
                capturedSessionToken = 7L,
                currentSessionToken = 8L,
            ),
        )
    }

    @Test
    fun staleForegroundContextCannotReachHardwareMutation() = runTest {
        var mutationCount = 0

        val staleRan = runCompactProfilePickerMutationIfCurrent(
            capturedForeground = app("com.game"),
            ensureCurrent = { false },
        ) {
            mutationCount++
        }
        val globalRan = runCompactProfilePickerMutationIfCurrent(
            capturedForeground = null,
            ensureCurrent = { false },
        ) {
            mutationCount++
        }

        assertFalse(staleRan)
        assertTrue(globalRan)
        assertEquals(1, mutationCount)
    }
}
