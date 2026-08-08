package com.aure.clustertune.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleWindowDisappearanceTrackerTest {
    private fun window(packageName: String, displayId: Int = 0, focused: Boolean = true) =
        VisibleAppWindow(packageName, displayId, isFocused = focused, isActive = focused)

    @Test
    fun initialPublishIsImmediateAndSchedulesNothing() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        val result = tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, nowMs = 0)

        assertEquals(setOf("app"), result.windowsByDisplay.values.flatten().map { it.packageName }.toSet())
        assertEquals(null, result.nextDeadlineMs)
    }

    @Test
    fun absenceIsRetainedAsInactiveUntilDeadline() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        val result = tracker.stabilize(emptyMap(), { true }, 100)

        assertEquals(400L, result.nextDeadlineMs)
        val retained = result.windowsByDisplay.getValue(0).single()
        assertFalse(retained.isFocused)
        assertFalse(retained.isActive)
    }

    @Test
    fun repeatedAbsenceDoesNotExtendOriginalDeadline() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 100)
        val result = tracker.stabilize(emptyMap(), { true }, 200)

        assertEquals(400L, result.nextDeadlineMs)
    }

    @Test
    fun absenceExpiresAtExactDeadline() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 0)
        val result = tracker.stabilize(emptyMap(), { true }, 300)

        assertTrue(result.windowsByDisplay.isEmpty())
        assertEquals(null, result.nextDeadlineMs)
    }

    @Test
    fun reappearanceCancelsPendingExpiryAndFreshAbsenceStartsNewGrace() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 100)
        val present = tracker.stabilize(mapOf(0 to listOf(window("replacement"))), { true }, 200)
        assertEquals(null, present.nextDeadlineMs)

        val absentAgain = tracker.stabilize(emptyMap(), { true }, 250)
        assertEquals(550L, absentAgain.nextDeadlineMs)
        assertEquals("replacement", absentAgain.windowsByDisplay.getValue(0).single().packageName)
    }

    @Test
    fun oneDisplayExpiryPreservesAnotherDisplay() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(
            mapOf(0 to listOf(window("first", 0)), 1 to listOf(window("second", 1))),
            { true },
            0,
        )
        tracker.stabilize(mapOf(1 to listOf(window("second", 1))), { true }, 100)
        val result = tracker.stabilize(mapOf(1 to listOf(window("second", 1))), { true }, 400)

        assertEquals(setOf(1), result.windowsByDisplay.keys)
        assertEquals("second", result.windowsByDisplay.getValue(1).single().packageName)
    }

    @Test
    fun displayOffDropsImmediatelyWithoutGrace() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        val result = tracker.stabilize(emptyMap(), { false }, 10)

        assertTrue(result.windowsByDisplay.isEmpty())
        assertEquals(null, result.nextDeadlineMs)
    }

    @Test
    fun clearResetsPublishedHistoryAndDeadlines() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 100)
        tracker.clear()
        val result = tracker.stabilize(emptyMap(), { true }, 200)

        assertTrue(result.windowsByDisplay.isEmpty())
        assertEquals(null, result.nextDeadlineMs)
    }

    @Test
    fun pauseRetainsPublishedWindowsButDropsOldDeadline() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 100)
        tracker.pause()

        val result = tracker.stabilize(emptyMap(), { true }, 200)
        assertEquals(500L, result.nextDeadlineMs)
        assertEquals("app", result.windowsByDisplay.getValue(0).single().packageName)
    }

    @Test
    fun postPauseAbsenceExpiresAtFreshDeadlineWithoutExtension() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(mapOf(0 to listOf(window("app"))), { true }, 0)
        tracker.stabilize(emptyMap(), { true }, 100)
        tracker.pause()
        tracker.stabilize(emptyMap(), { true }, 200)
        tracker.stabilize(emptyMap(), { true }, 400)
        val expired = tracker.stabilize(emptyMap(), { true }, 500)

        assertTrue(expired.windowsByDisplay.isEmpty())
        assertEquals(null, expired.nextDeadlineMs)
    }

    @Test
    fun removeDisplayDropsOnlyRequestedDisplay() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(
            mapOf(0 to listOf(window("first", 0)), 1 to listOf(window("second", 1))),
            { true },
            0,
        )
        tracker.removeDisplay(0)

        val result = tracker.stabilize(mapOf(1 to listOf(window("second", 1))), { true }, 100)
        assertEquals(setOf(1), result.windowsByDisplay.keys)
    }
}
