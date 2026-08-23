package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppResolverSelectionTest {
    @Test
    fun targetDisplayGameBeatsFocusedFrontendOnAnotherDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("game", 0, isActive = true)),
                1 to listOf(VisibleAppWindow("frontend", 1, isFocused = true, isActive = true)),
            ),
        )

        assertEquals("game", selectVisibleAppWindow(snapshot, targetDisplayId = 0)?.packageName)
    }

    @Test
    fun targetDisplayDoesNotFallBackToAnotherDisplayWhenEmpty() {
        val otherDisplay = VisibleAppWindow("game", 1, isFocused = true, isActive = true)
        assertNull(
            selectVisibleAppWindow(
                VisibleAppSnapshot(mapOf(1 to listOf(otherDisplay))),
                targetDisplayId = 0,
            ),
        )
    }

    @Test
    fun systemUiRemainsExplicitTargetDisplayCandidate() {
        val otherDisplay = VisibleAppWindow("game", 1, isFocused = true, isActive = true)
        assertEquals(
            "com.android.systemui",
            selectVisibleAppWindow(
                VisibleAppSnapshot(
                    mapOf(
                        0 to listOf(VisibleAppWindow("com.android.systemui", 0, isFocused = true)),
                        1 to listOf(otherDisplay),
                    ),
                ),
                targetDisplayId = 0,
            )?.packageName,
        )
    }

    @Test
    fun vendorAssistantDoesNotReplaceVisibleGame() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0),
                    VisibleAppWindow(
                        "com.ayn.gameassistant",
                        0,
                        isFocused = true,
                        isActive = true,
                    ),
                ),
            ),
        )

        assertEquals(
            "com.example.game",
            selectVisibleAppWindow(
                snapshot,
                targetDisplayId = 0,
                excludedPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
            )?.packageName,
        )
    }

    @Test
    fun legacyRetroidAssistantIsAlsoExcluded() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0, isActive = true),
                    VisibleAppWindow("com.retroidpocket.gameassistant", 0, isFocused = true),
                ),
            ),
        )

        assertEquals(
            "com.example.game",
            selectVisibleAppWindow(snapshot, 0, VENDOR_GAME_ASSISTANT_PACKAGES)?.packageName,
        )
    }
}
