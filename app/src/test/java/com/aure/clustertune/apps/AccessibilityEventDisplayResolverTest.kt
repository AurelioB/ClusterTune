package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityEventDisplayResolverTest {
    @Test
    fun `reported display wins when the platform provides it`() {
        assertEquals(
            7,
            resolveAccessibilityEventDisplayId(
                reportedDisplayId = 7,
                eventWindowId = 42,
                windowIdsByDisplay = mapOf(2 to listOf(42)),
            ),
        )
    }

    @Test
    fun `window id resolves an event display on Android 12`() {
        assertEquals(
            2,
            resolveAccessibilityEventDisplayId(
                reportedDisplayId = null,
                eventWindowId = 42,
                windowIdsByDisplay = mapOf(
                    0 to listOf(10, 11),
                    2 to listOf(40, 42),
                ),
            ),
        )
    }

    @Test
    fun `unknown window falls back to the default display`() {
        assertEquals(
            0,
            resolveAccessibilityEventDisplayId(
                reportedDisplayId = -1,
                eventWindowId = 99,
                windowIdsByDisplay = mapOf(3 to listOf(12)),
            ),
        )
    }
}
