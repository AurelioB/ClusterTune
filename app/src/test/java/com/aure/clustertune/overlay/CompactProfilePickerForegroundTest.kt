package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.ui.CompactOverlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val ignored = setOf("com.aure.clustertune", SYSTEM_UI_PACKAGE)
    private fun app(packageName: String, label: String = packageName) =
        ForegroundAppInfo(packageName, label)

    @Test
    fun nullDetectionClearsContext() {
        assertNull(updateCompactProfilePickerForeground(app("com.game"), null, ignored))
    }

    @Test
    fun ignoredDetectionPreservesContext() {
        val current = app("com.game")
        assertEquals(current, updateCompactProfilePickerForeground(current, app(SYSTEM_UI_PACKAGE), ignored))
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
