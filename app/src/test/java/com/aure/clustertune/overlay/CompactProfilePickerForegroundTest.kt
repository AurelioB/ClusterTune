package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.VENDOR_GAME_ASSISTANT_PACKAGES
import com.aure.clustertune.apps.VisibleAppSnapshot
import com.aure.clustertune.apps.VisibleAppWindow
import com.aure.clustertune.apps.mergeEventFallbackWindows
import com.aure.clustertune.apps.selectVisibleAppWindow
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.ui.CompactOverlayMode
import org.junit.Assert.assertEquals
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

    private val ownPackage = "com.aure.clustertune"
    private val ignored = compactProfilePickerExcludedPackages(ownPackage)
    private fun app(packageName: String, label: String = packageName) =
        ForegroundAppInfo(packageName, label)

    @Test
    fun unresolvedWithoutExactWindowIdentityClearsEstablishedContext() {
        assertNull(
            updateCompactProfilePickerForeground(
                current = app("com.game"),
                detected = null,
                ignoredPackages = ignored,
            ),
        )
    }

    @Test
    fun initialNullDetectionLeavesContextEmpty() {
        assertNull(
            updateCompactProfilePickerForeground(
                current = null,
                detected = null,
                ignoredPackages = ignored,
            ),
        )
    }

    @Test
    fun ignoredDetectionPreservesContext() {
        val current = app("com.game")
        assertEquals(current, updateCompactProfilePickerForeground(current, app(SYSTEM_UI_PACKAGE), ignored))
    }

    @Test
    fun assistantOnlySnapshotUsesDisplayFallbackAndPreservesGame() {
        val assistantPackage = VENDOR_GAME_ASSISTANT_PACKAGES.first()
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mergeEventFallbackWindows(
                observed = mapOf(
                    0 to listOf(VisibleAppWindow(assistantPackage, 0, isFocused = true)),
                ),
                eventFallbacks = mapOf(0 to "com.game"),
                obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
            ),
            isInteractive = true,
        )
        val selected = selectVisibleAppWindow(
            snapshot = snapshot,
            targetDisplayId = 0,
            excludedPackages = ignored,
        )

        assertEquals("com.game", selected?.packageName)
        assertEquals(
            app("com.game"),
            updateCompactProfilePickerForeground(
                current = app("com.game"),
                detected = selected?.packageName?.let { app(it) },
                ignoredPackages = ignored,
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
    fun systemUiThenGameEstablishesContext() {
        val afterSystemUi = updateCompactProfilePickerForeground(null, app(SYSTEM_UI_PACKAGE), ignored)
        assertEquals(app("com.game"), updateCompactProfilePickerForeground(afterSystemUi, app("com.game"), ignored))
    }

    @Test
    fun trackedGameSystemUiThenSameGameRemainsGame() {
        val current = app("com.game")
        val afterSystemUi = updateCompactProfilePickerForeground(current, app(SYSTEM_UI_PACKAGE), ignored)
        assertEquals(current, updateCompactProfilePickerForeground(afterSystemUi, current, ignored))
    }

    @Test
    fun gameToLauncherReplacesContext() {
        assertEquals(
            app("com.android.launcher"),
            updateCompactProfilePickerForeground(app("com.game"), app("com.android.launcher"), ignored),
        )
    }
}
