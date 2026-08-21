package com.aure.clustertune.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsDefaultsTest {

    @Test
    fun `fresh settings enable automatic stable update checks`() {
        val settings = AppSettings()

        assertTrue(settings.automaticUpdateChecksEnabled)
        assertFalse(settings.includePrereleaseUpdates)
    }

    @Test
    fun `explicitly disabled automatic checks remain disabled`() {
        val settings = AppSettings(automaticUpdateChecksEnabled = false)

        assertFalse(settings.automaticUpdateChecksEnabled)
        assertFalse(settings.includePrereleaseUpdates)
    }
}
