package com.aure.clustertune.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDefaultsTest {
    @Test
    fun `disabled experimental features fall back from HUD without losing the saved tile choice`() {
        val settings = AppSettings(tileTapBehavior = TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD)
        assertEquals(TileInteractionBehavior.SHOW_DIALOG, settings.effectiveTileTapBehavior)
        assertEquals(TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD, settings.tileTapBehavior)
        assertEquals(TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD, settings.copy(autoTuneEnabled = true).effectiveTileTapBehavior)
        TileInteractionBehavior.entries.filter { it != TileInteractionBehavior.TOGGLE_PERFORMANCE_HUD }.forEach { behavior ->
            assertEquals(behavior, settings.copy(tileTapBehavior = behavior).effectiveTileTapBehavior)
        }
    }

    @Test
    fun `fresh settings enable automatic stable update checks`() {
        val settings = AppSettings()

        assertEquals(TileInteractionBehavior.SHOW_DIALOG, settings.tileTapBehavior)
        assertTrue(settings.automaticUpdateChecksEnabled)
        assertFalse(settings.includePrereleaseUpdates)
        assertFalse(settings.autoTuneEnabled)
    }

    @Test
    fun `explicitly disabled automatic checks remain disabled`() {
        val settings = AppSettings(automaticUpdateChecksEnabled = false)

        assertFalse(settings.automaticUpdateChecksEnabled)
        assertFalse(settings.includePrereleaseUpdates)
    }
}
