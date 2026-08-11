package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompactProfilePickerForegroundTest {
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
