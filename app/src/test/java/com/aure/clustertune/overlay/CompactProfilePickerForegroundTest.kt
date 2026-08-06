package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProfilePickerForegroundTest {
    private val ignored = setOf("com.aure.clustertune", SYSTEM_UI_PACKAGE)
    private fun app(packageName: String, label: String = packageName) =
        ForegroundAppInfo(packageName = packageName, label = label)

    @Test
    fun firstNullDetectionKeepsContextDuringGracePeriod() {
        val state = CompactProfilePickerForegroundState("com.example.app", app("com.example.app"))

        val update = updateCompactProfilePickerForeground(state, null)

        assertEquals(state.foregroundApp, update.state.foregroundApp)
        assertEquals(1, update.state.consecutiveNullDetections)
        assertFalse(update.dismissRequested)
    }

    @Test
    fun secondConsecutiveNullClearsContextAndRequestsDismissal() {
        val state = CompactProfilePickerForegroundState("com.example.app", app("com.example.app"), 1)

        val update = updateCompactProfilePickerForeground(state, null)

        assertNull(update.state.foregroundApp)
        assertTrue(update.dismissRequested)
    }

    @Test
    fun firstNonNullDetectionEstablishesContextWithoutDismissal() {
        val update = updateCompactProfilePickerForeground(
            CompactProfilePickerForegroundState(),
            app("com.example.app", "Example"),
        )

        assertEquals("com.example.app", update.state.trackedPackageName)
        assertEquals("Example", update.state.foregroundApp?.label)
        assertFalse(update.dismissRequested)
    }

    @Test
    fun samePackageKeepsCachedContextAndRemainsOpen() {
        val state = CompactProfilePickerForegroundState("com.example.app", app("com.example.app", "Old"))
        val update = updateCompactProfilePickerForeground(
            state,
            app("com.example.app", "Updated"),
        )

        assertEquals("Old", update.state.foregroundApp?.label)
        assertTrue(update.state.foregroundApp === state.foregroundApp)
        assertFalse(update.dismissRequested)
    }

    @Test
    fun nonNullDetectionResetsTransientNullGrace() {
        val state = CompactProfilePickerForegroundState("com.example.app", app("com.example.app"), 1)

        val update = updateCompactProfilePickerForeground(state, app("com.example.app", "Ignored"))

        assertEquals(0, update.state.consecutiveNullDetections)
        assertFalse(update.dismissRequested)
    }

    @Test
    fun changedPackageCarriesNewContextAndRequestsDismissal() {
        val update = updateCompactProfilePickerForeground(
            CompactProfilePickerForegroundState("com.example.app", app("com.example.app")),
            app("com.android.launcher", "Home"),
        )

        assertEquals("com.android.launcher", update.state.foregroundApp?.packageName)
        assertTrue(update.dismissRequested)
        assertNull(update.state.foregroundApp?.icon)
    }

    @Test
    fun systemUiSampleDoesNotClaimIdentityBeforeGameAppears() {
        val state = CompactProfilePickerForegroundState()
        val ignoredUpdate = updateCompactProfilePickerForeground(state, app("com.android.systemui"), ignored)
        val game = updateCompactProfilePickerForeground(ignoredUpdate.state, app("com.game"), ignored)
        assertFalse(ignoredUpdate.dismissRequested)
        assertEquals("com.game", game.state.trackedPackageName)
        assertFalse(game.dismissRequested)
    }

    @Test
    fun ownAppTransientThenGameEstablishesWithoutDismissal() {
        val own = updateCompactProfilePickerForeground(CompactProfilePickerForegroundState(), app("com.aure.clustertune"), ignored)
        val game = updateCompactProfilePickerForeground(own.state, app("com.game"), ignored)
        assertFalse(own.dismissRequested)
        assertEquals("com.game", game.state.trackedPackageName)
        assertFalse(game.dismissRequested)
    }

    @Test
    fun ignoredSampleAfterTrackingDoesNotDismiss() {
        val update = updateCompactProfilePickerForeground(
            CompactProfilePickerForegroundState("com.game", app("com.game")),
            app(SYSTEM_UI_PACKAGE),
            ignored,
        )
        assertFalse(update.dismissRequested)
        assertEquals("com.game", update.state.trackedPackageName)
    }
}
