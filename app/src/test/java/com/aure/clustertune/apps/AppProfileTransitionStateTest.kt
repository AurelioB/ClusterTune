package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProfileTransitionStateTest {
    private val assignment = AppProfileAssignment("assigned.app", "Assigned", profileId = "small")

    @Test fun `assigned null assigned does not restore or reapply`() {
        val state = AppProfileTransitionState(100)
        assertTrue(state.observe(0, "assigned.app", listOf(assignment)) is AppProfileTransitionState.Action.Apply)
        state.onApplied(assignment)
        assertEquals(AppProfileTransitionState.Action.None, state.observe(10, null, listOf(assignment)))
        assertEquals(AppProfileTransitionState.Action.None, state.observe(20, "assigned.app", listOf(assignment)))
    }

    @Test fun `transient overlay does not restore`() {
        val state = AppProfileTransitionState(100)
        state.onApplied(assignment)
        assertEquals(AppProfileTransitionState.Action.None, state.observe(0, "overlay", listOf(assignment), transientForeground = true))
        assertEquals(AppProfileTransitionState.Action.None, state.observe(100, "overlay", listOf(assignment), transientForeground = true))
        assertEquals(AppProfileTransitionState.Action.None, state.observe(10_000, null, listOf(assignment)))
    }

    @Test fun `stable unassigned restores after grace`() {
        val state = AppProfileTransitionState(100)
        state.onApplied(assignment)
        assertEquals(AppProfileTransitionState.Action.None, state.observe(10, "other.app", listOf(assignment)))
        assertEquals(AppProfileTransitionState.Action.Restore, state.observe(110, "other.app", listOf(assignment)))
    }

    @Test fun `failed apply retries and target change applies`() {
        val state = AppProfileTransitionState(100)
        assertTrue(state.observe(0, "assigned.app", listOf(assignment)) is AppProfileTransitionState.Action.Apply)
        assertTrue(state.observe(1, "assigned.app", listOf(assignment)) is AppProfileTransitionState.Action.Apply)
        state.onApplied(assignment)
        val changed = assignment.copy(profileId = "large")
        assertEquals(AppProfileTransitionState.Action.Apply(changed), state.observe(2, "assigned.app", listOf(changed)))
    }

    @Test fun `custom gpu target change applies`() {
        val state = AppProfileTransitionState(100)
        val gpuAssignment = assignment.copy(customGpuMaxFrequencyHz = 400_000_000)
        val changed = gpuAssignment.copy(customGpuMaxFrequencyHz = 300_000_000)

        assertEquals(
            AppProfileTransitionState.Action.Apply(gpuAssignment),
            state.observe(0, "assigned.app", listOf(gpuAssignment)),
        )
        state.onApplied(gpuAssignment)
        assertEquals(
            AppProfileTransitionState.Action.Apply(changed),
            state.observe(1, "assigned.app", listOf(changed)),
        )
    }

    @Test fun `restore retries until success and state survives service style restart`() {
        val state = AppProfileTransitionState(0)
        state.onApplied(assignment)
        assertEquals(AppProfileTransitionState.Action.Restore, state.observe(1, "other.app", listOf(assignment)))
        assertEquals(AppProfileTransitionState.Action.Restore, state.observe(2, "other.app", listOf(assignment)))
        state.onRestored()
        assertEquals(AppProfileTransitionState.Action.None, state.observe(3, "other.app", listOf(assignment)))
    }

    @Test fun `service style restart applies assigned foreground once`() {
        val restarted = AppProfileTransitionState()
        assertEquals(
            AppProfileTransitionState.Action.Apply(assignment),
            restarted.observe(10, "assigned.app", listOf(assignment)),
        )
        restarted.onApplied(assignment)
        assertEquals(AppProfileTransitionState.Action.None, restarted.observe(11, "assigned.app", listOf(assignment)))
    }

    @Test fun `service style restart restores when foreground is unassigned`() {
        val restarted = AppProfileTransitionState()
        assertEquals(AppProfileTransitionState.Action.Restore, restarted.observe(10, "other.app", listOf(assignment)))
        assertEquals(AppProfileTransitionState.Action.Restore, restarted.observe(11, "other.app", listOf(assignment)))
        restarted.onRestored()
        assertEquals(AppProfileTransitionState.Action.None, restarted.observe(12, "other.app", listOf(assignment)))
    }

    @Test fun `service style restart waits through ambiguous foreground`() {
        val restarted = AppProfileTransitionState()

        assertEquals(
            AppProfileTransitionState.Action.None,
            restarted.observe(10, null, listOf(assignment), transientForeground = true),
        )
    }
}
