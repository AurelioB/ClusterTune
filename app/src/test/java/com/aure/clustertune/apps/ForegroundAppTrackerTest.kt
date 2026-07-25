package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundAppTrackerTest {

    @Test
    fun `opening a game keeps the emulator package foreground`() {
        val tracker = ForegroundAppTracker()

        tracker.onActivityResumed(EMULATOR_PACKAGE, "LauncherActivity")
        tracker.onActivityPaused(EMULATOR_PACKAGE, "LauncherActivity")
        tracker.onActivityResumed(EMULATOR_PACKAGE, "EmulationActivity")
        tracker.onActivityStopped()

        assertEquals(EMULATOR_PACKAGE, tracker.foregroundPackage)
    }

    @Test
    fun `late pause from another activity in the same app is ignored`() {
        val tracker = ForegroundAppTracker()

        tracker.onActivityResumed(EMULATOR_PACKAGE, "LauncherActivity")
        tracker.onActivityResumed(EMULATOR_PACKAGE, "EmulationActivity")
        tracker.onActivityPaused(EMULATOR_PACKAGE, "LauncherActivity")

        assertEquals(EMULATOR_PACKAGE, tracker.foregroundPackage)
    }

    @Test
    fun `pausing the current activity clears the foreground package`() {
        val tracker = ForegroundAppTracker()

        tracker.onActivityResumed(EMULATOR_PACKAGE, "EmulationActivity")
        tracker.onActivityPaused(EMULATOR_PACKAGE, "EmulationActivity")

        assertNull(tracker.foregroundPackage)
    }

    @Test
    fun `late background event from previous app does not clear current app`() {
        val tracker = ForegroundAppTracker()

        tracker.onActivityResumed("com.example.launcher", "LauncherActivity")
        tracker.onActivityResumed(EMULATOR_PACKAGE, "EmulationActivity")
        tracker.onActivityPaused("com.example.launcher", "LauncherActivity")

        assertEquals(EMULATOR_PACKAGE, tracker.foregroundPackage)
    }

    private companion object {
        const val EMULATOR_PACKAGE = "org.example.emulator"
    }
}
