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

        AdaptiveTuneRuntime.finish(first, "Old session finished")

        assertFalse(AdaptiveTuneRuntime.isCurrent(first))
        assertTrue(AdaptiveTuneRuntime.isCurrent(second))
        assertTrue(AdaptiveTuneRuntime.state.value.active)
        assertEquals("second", AdaptiveTuneRuntime.state.value.packageName)
    }

    @Test
    fun `replaced coordinator cannot invalidate its successor`() {
        val oldLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        val newLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        val session = AdaptiveTuneRuntime.begin("new.game", "New game", 120)

        assertFalse(
            AdaptiveTuneRuntime.invalidateIfCoordinatorCurrent(
                oldLease,
                "Old coordinator stopped",
            ),
        )
        assertTrue(AdaptiveTuneRuntime.isCurrent(session))
        assertTrue(AdaptiveTuneRuntime.state.value.active)
        assertEquals("new.game", AdaptiveTuneRuntime.state.value.packageName)

        assertTrue(
            AdaptiveTuneRuntime.invalidateIfCoordinatorCurrent(
                newLease,
                "Current coordinator stopped",
            ),
        )
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("Current coordinator stopped", AdaptiveTuneRuntime.state.value.message)

        val terminal = AdaptiveTuneRuntime.begin("new.game", "New game", 120)
        AdaptiveTuneRuntime.finish(terminal, "New coordinator terminal state")
        assertFalse(AdaptiveTuneRuntime.clearInactiveIfCoordinatorCurrent(oldLease))
        assertFalse(
            AdaptiveTuneRuntime.reportCleanupFailureIfCoordinatorCurrent(
                oldLease,
                "Old coordinator cleanup failed",
            ),
        )
        assertEquals("New coordinator terminal state", AdaptiveTuneRuntime.state.value.message)
        assertTrue(AdaptiveTuneRuntime.clearInactiveIfCoordinatorCurrent(newLease))
        assertEquals(AdaptiveTuneRuntimeState(), AdaptiveTuneRuntime.state.value)
    }

    @Test
    fun `replacement lease invalidates an old active generation`() {
        val oldLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        val oldGeneration = requireNotNull(
            AdaptiveTuneRuntime.beginIfCoordinatorCurrent(
                coordinatorLease = oldLease,
                packageName = "old.game",
                appLabel = "Old game",
                targetFps = 60,
            ),
        )

        val replacementLease = AdaptiveTuneRuntime.claimCoordinatorLease()

        assertFalse(AdaptiveTuneRuntime.isCurrent(oldGeneration))
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("App profile coordinator replaced", AdaptiveTuneRuntime.state.value.message)
        assertTrue(AdaptiveTuneRuntime.isCoordinatorCurrent(replacementLease))
    }

    @Test
    fun `stopped coordinator retains cleanup ownership but cannot start a session`() {
        val lease = AdaptiveTuneRuntime.claimCoordinatorLease()

        assertTrue(
            AdaptiveTuneRuntime.stopCoordinatorIfCurrent(
                lease,
                "Coordinator stopped",
            ),
        )

        assertTrue(AdaptiveTuneRuntime.isCoordinatorCurrent(lease))
        assertEquals(
            null,
            AdaptiveTuneRuntime.beginIfCoordinatorCurrent(
                coordinatorLease = lease,
                packageName = "late.game",
                appLabel = "Late game",
                targetFps = 60,
            ),
        )

        val replacement = AdaptiveTuneRuntime.claimCoordinatorLease()
        assertTrue(
            AdaptiveTuneRuntime.beginIfCoordinatorCurrent(
                coordinatorLease = replacement,
                packageName = "new.game",
                appLabel = "New game",
                targetFps = 120,
            ) != null,
        )
    }

    @Test
    fun `retry starts only while the completed generation is still current`() {
        val first = AdaptiveTuneRuntime.begin("game", "Game", 60)

        val retry = AdaptiveTuneRuntime.beginIfCurrent(first, "game", "Game", 60)

        requireNotNull(retry)
        assertTrue(AdaptiveTuneRuntime.isCurrent(retry))
        assertEquals("Retrying Auto Tune", AdaptiveTuneRuntime.state.value.message)

        AdaptiveTuneRuntime.invalidate("Manual profile selected")

        assertEquals(
            null,
            AdaptiveTuneRuntime.beginIfCurrent(retry, "game", "Game", 60),
        )
        assertFalse(AdaptiveTuneRuntime.state.value.active)
        assertEquals("Manual profile selected", AdaptiveTuneRuntime.state.value.message)
    }

    @Test
    fun `inactive invalidation does not create a terminal presentation`() {
        AdaptiveTuneRuntime.invalidate("Fixed profile selected")

        assertEquals(AdaptiveTuneRuntimeState(), AdaptiveTuneRuntime.state.value)
    }

    @Test
    fun `terminal presentation clears after its app loses ownership`() {
        val token = AdaptiveTuneRuntime.begin("game", "Game", 60)
        AdaptiveTuneRuntime.finish(token, "Frame telemetry became unavailable")

        AdaptiveTuneRuntime.clearInactive()

        assertEquals(AdaptiveTuneRuntimeState(), AdaptiveTuneRuntime.state.value)
    }

    @Test
    fun `cleanup failure replaces an inactive message but not a newer active owner`() {
        val first = AdaptiveTuneRuntime.begin("game", "Game", 60)
        AdaptiveTuneRuntime.invalidate("Display turned off")

        AdaptiveTuneRuntime.reportCleanupFailure("  Auto Tune restoration failed: max node  ")

        assertEquals(
            "Auto Tune restoration failed: max node",
            AdaptiveTuneRuntime.state.value.message,
        )

        val second = AdaptiveTuneRuntime.begin("other", "Other", 120)
        AdaptiveTuneRuntime.reportCleanupFailure("Old cleanup failed")

        assertTrue(AdaptiveTuneRuntime.isCurrent(second))
        assertTrue(AdaptiveTuneRuntime.state.value.active)
        assertEquals("other", AdaptiveTuneRuntime.state.value.packageName)
        assertEquals("Starting Auto Tune", AdaptiveTuneRuntime.state.value.message)
        assertFalse(AdaptiveTuneRuntime.isCurrent(first))
    }
}
