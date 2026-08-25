package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppResolverSelectionTest {
    @Test
    fun packageSpecificSelectionFindsRequestedVisibleApp() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("game", 0, isActive = true),
                    VisibleAppWindow("frontend", 0, isFocused = true, isActive = true),
                ),
            ),
        )

        assertEquals(
            VisibleAppWindow("game", 0, isActive = true),
            selectVisibleAppWindowForPackage(snapshot, packageName = "game"),
        )
    }

    @Test
    fun packageSpecificSelectionCanBeRestrictedToOneDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("game", 0, isFocused = true)),
                2 to listOf(VisibleAppWindow("game", 2, isActive = true)),
            ),
        )

        assertEquals(
            2,
            selectVisibleAppWindowForPackage(snapshot, "game", targetDisplayId = 2)?.displayId,
        )
        assertNull(selectVisibleAppWindowForPackage(snapshot, "other", targetDisplayId = 2))
    }

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
    fun focusedAppBeatsPreferredActiveAppOnTheSameDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0, isActive = true),
                    VisibleAppWindow(
                        "com.example.frontend",
                        0,
                        isFocused = true,
                        isActive = true,
                    ),
                ),
            ),
        )

        assertEquals(
            "com.example.frontend",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                preferredPackageName = "com.example.game",
            )?.packageName,
        )
    }

    @Test
    fun activeAppBeatsPreferredVisibleInactiveAppOnTheSameDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.oldgame", 0),
                    VisibleAppWindow("com.example.newgame", 0, isActive = true),
                ),
            ),
        )

        assertEquals(
            "com.example.newgame",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                preferredPackageName = "com.example.oldgame",
            )?.packageName,
        )
    }

    @Test
    fun staleRecentPackageDoesNotBeatFocusedAppDuringInitialResolution() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.stale", 0),
                    VisibleAppWindow("com.example.game", 0, isFocused = true, isActive = true),
                ),
            ),
            recentPackageByDisplay = mapOf(0 to "com.example.stale"),
        )

        assertEquals(
            "com.example.game",
            selectVisibleAppWindow(snapshot, targetDisplayId = 0)?.packageName,
        )
    }

    @Test
    fun preferredPackageBreaksTiesBetweenEquallyActiveWindows() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0, isActive = true),
                    VisibleAppWindow("com.example.frontend", 0, isActive = true),
                ),
            ),
        )

        assertEquals(
            "com.example.game",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                preferredPackageName = "com.example.game",
            )?.packageName,
        )
    }

    @Test
    fun recentWindowEventBeatsPreferredAppWhenForegroundFlagsAreTied() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.oldgame", 0, isActive = true),
                    VisibleAppWindow("com.example.newgame", 0, isActive = true),
                ),
            ),
            mostRecentAppIdentity = RecentAppIdentity(0, "com.example.newgame"),
            recentPackageByDisplay = mapOf(0 to "com.example.newgame"),
        )

        assertEquals(
            "com.example.newgame",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                preferredPackageName = "com.example.oldgame",
            )?.packageName,
        )
    }

    @Test
    fun missingPreferredAppFallsBackToTheCurrentFocusedWindow() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.launcher", 0),
                    VisibleAppWindow("com.example.newgame", 0, isFocused = true, isActive = true),
                ),
            ),
        )

        assertEquals(
            "com.example.newgame",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                preferredPackageName = "com.example.closedgame",
            )?.packageName,
        )
    }

    @Test
    fun excludedPreferredAppCannotClaimTheOverlay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0, isActive = true),
                    VisibleAppWindow("com.example.overlay", 0, isFocused = true, isActive = true),
                ),
            ),
        )

        assertEquals(
            "com.example.game",
            selectVisibleAppWindow(
                snapshot = snapshot,
                targetDisplayId = 0,
                excludedPackages = setOf("com.example.overlay"),
                preferredPackageName = "com.example.overlay",
            )?.packageName,
        )
    }

    @Test
    fun reportedOdinAssistantDoesNotReplaceVisibleGame() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.game", 0),
                    VisibleAppWindow(
                        "com.odin.gameassistant",
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
