package com.aure.clustertune.autotune

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveTuneRuntimeTest {
    @After
    fun tearDown() = AdaptiveTuneRuntime.resetForTest()

    @Test
    fun `manual invalidation rejects delayed automatic publication`() {
        val token = AdaptiveTuneRuntime.begin("game", "Game", 60)
        AdaptiveTuneRuntime.invalidate("Manual profile selected")

        AdaptiveTuneRuntime.publish(token) { it.copy(measuredFps = 60.0) }

        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals(null, AdaptiveTuneRuntime.state.value.measuredFps)
        assertEquals("Manual profile selected", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `new session supersedes the previous token`() {
        val first = AdaptiveTuneRuntime.begin("first", "First", 30)
        val second = AdaptiveTuneRuntime.begin("second", "Second", 120)

        assertFalse(AdaptiveTuneRuntime.isCurrent(first))
        assertTrue(AdaptiveTuneRuntime.isCurrent(second))
        assertEquals("second", AdaptiveTuneRuntime.state.value.packageName)
    }
}
