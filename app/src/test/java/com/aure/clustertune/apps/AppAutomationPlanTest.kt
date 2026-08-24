package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAutomationPlanTest {
    @Test
    fun `focused automatic assignment owns tuning across displays`() {
        val automatic = automatic("game", 60)
        val static = static("video")
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("video", 0, isActive = true)),
                1 to listOf(VisibleAppWindow("game", 1, isFocused = true, isActive = true)),
            ),
            isInteractive = true,
        )

        val plan = resolveAppAutomationPlan(snapshot, listOf(automatic, static))

        assertEquals("game", plan.foregroundPackageName)
        assertEquals(automatic, plan.autoTuneAssignment)
        assertEquals(listOf(static), plan.staticAssignments)
    }

    @Test
    fun `visible background automatic assignment does not steal global limits`() {
        val automatic = automatic("game", 120)
        val static = static("reader")
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("game", 0, isActive = true),
                    VisibleAppWindow("reader", 0, isFocused = true, isActive = true),
                ),
            ),
            isInteractive = true,
        )

        val plan = resolveAppAutomationPlan(snapshot, listOf(automatic, static))

        assertEquals("reader", plan.foregroundPackageName)
        assertNull(plan.autoTuneAssignment)
        assertEquals(listOf(static), plan.staticAssignments)
    }

    @Test
    fun `screen off produces no automatic owner`() {
        val plan = resolveAppAutomationPlan(
            snapshot = VisibleAppSnapshot(
                windowsByDisplay = mapOf(0 to listOf(VisibleAppWindow("game", 0, isFocused = true))),
                isInteractive = false,
            ),
            assignments = listOf(automatic("game", 30)),
        )

        assertNull(plan.foregroundPackageName)
        assertNull(plan.autoTuneAssignment)
        assertEquals(emptyList<AppProfileAssignment>(), plan.staticAssignments)
    }

    @Test
    fun `recent window event breaks an ambiguous active-window tie`() {
        val first = automatic("alpha.game", 30)
        val recent = automatic("zeta.game", 60)
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("alpha.game", 0, isActive = true),
                    VisibleAppWindow("zeta.game", 0, isActive = true),
                ),
            ),
            isInteractive = true,
            recentPackageByDisplay = mapOf(0 to "zeta.game"),
        )

        val plan = resolveAppAutomationPlan(snapshot, listOf(first, recent))

        assertEquals("zeta.game", plan.foregroundPackageName)
        assertEquals(recent, plan.autoTuneAssignment)
    }

    @Test
    fun `active window outranks a recent inactive window`() {
        val recent = automatic("recent.game", 30)
        val active = automatic("active.game", 60)
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("recent.game", 0, isActive = false),
                    VisibleAppWindow("active.game", 0, isActive = true),
                ),
            ),
            isInteractive = true,
            recentPackageByDisplay = mapOf(0 to "recent.game"),
        )

        val plan = resolveAppAutomationPlan(snapshot, listOf(recent, active))

        assertEquals("active.game", plan.foregroundPackageName)
        assertEquals(active, plan.autoTuneAssignment)
    }

    @Test
    fun `globally latest event breaks equal focus ties across displays`() {
        val defaultDisplay = automatic("default.game", 30)
        val latest = automatic("external.game", 60)
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("default.game", 0, isFocused = true, isActive = true),
                ),
                2 to listOf(
                    VisibleAppWindow("external.game", 2, isFocused = true, isActive = true),
                ),
            ),
            isInteractive = true,
            recentPackageByDisplay = mapOf(
                0 to "default.game",
                2 to "external.game",
            ),
            mostRecentAppIdentity = RecentAppIdentity(2, "external.game"),
        )

        val plan = resolveAppAutomationPlan(snapshot, listOf(defaultDisplay, latest))

        assertEquals("external.game", plan.foregroundPackageName)
        assertEquals(latest, plan.autoTuneAssignment)
    }

    @Test
    fun `excluded overlay cannot replace the automatic owner`() {
        val automatic = automatic("game", 60)
        val excludedStatic = static("assistant")
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("game", 0, isActive = true),
                    VisibleAppWindow("assistant", 0, isFocused = true, isActive = true),
                ),
            ),
            isInteractive = true,
        )

        val plan = resolveAppAutomationPlan(
            snapshot,
            listOf(automatic, excludedStatic),
            setOf("assistant"),
        )

        assertEquals(automatic, plan.autoTuneAssignment)
        assertEquals(emptyList<AppProfileAssignment>(), plan.staticAssignments)
    }

    private fun automatic(packageName: String, targetFps: Int) = AppProfileAssignment(
        packageName = packageName,
        appLabel = packageName,
        autoTuneTargetFps = targetFps,
    )

    private fun static(packageName: String) = AppProfileAssignment(
        packageName = packageName,
        appLabel = packageName,
        profileId = "small",
    )
}
