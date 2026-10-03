package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAutomationPlanTest {
    private fun enabledPlan(
        snapshot: VisibleAppSnapshot,
        assignments: List<AppProfileAssignment>,
        excludedPackages: Set<String> = emptySet(),
    ) = resolveAppAutomationPlan(snapshot, assignments, excludedPackages, autoTuneEnabled = true)

    @Test fun `experimental tuning is off by default even with a saved assignment`() {
        val automatic = automatic("game", 60)
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(0 to listOf(VisibleAppWindow("game", 0, isFocused = true))),
            isInteractive = true,
        )
        assertNull(resolveAppAutomationPlan(snapshot, listOf(automatic)).autoTuneAssignment)
        assertEquals(automatic, enabledPlan(snapshot, listOf(automatic)).autoTuneAssignment)
        assertNull(resolveAppAutomationPlan(snapshot, listOf(automatic), autoTuneEnabled = false).autoTuneAssignment)
        assertEquals(60, automatic.autoTuneTargetFps) // Disabling never rewrites the saved target.
    }

    @Test fun `disabling tuning keeps static app assignments available`() {
        val fixed = AppProfileAssignment("fixed", "Fixed", profileId = "small")
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(0 to listOf(VisibleAppWindow("game", 0, isFocused = true), VisibleAppWindow("fixed", 0))),
            isInteractive = true,
        )
        val plan = resolveAppAutomationPlan(snapshot, listOf(automatic("game", 60), fixed))
        assertNull(plan.autoTuneAssignment)
        assertEquals(listOf(fixed), plan.staticAssignments)
    }

    @Test
    fun `automatic runtime target is capped by foreground display refresh rate`() {
        val configured = automatic("game", 120)
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                2 to listOf(VisibleAppWindow("game", 2, isFocused = true, isActive = true)),
            ),
            isInteractive = true,
            refreshRateFpsByDisplay = mapOf(2 to 60),
        )

        val plan = enabledPlan(snapshot, listOf(configured))

        assertEquals(configured, plan.autoTuneAssignment)
        assertEquals(120, plan.autoTuneAssignment?.autoTuneTargetFps)
        assertEquals(60, plan.effectiveAutoTuneTargetFps)
        assertEquals(2, plan.foregroundDisplayId)
        assertEquals(60, plan.foregroundDisplayRefreshRateFps)
    }

    @Test
    fun `automatic runtime target keeps lower configured target`() {
        val plan = enabledPlan(
            snapshot = VisibleAppSnapshot(
                windowsByDisplay = mapOf(
                    0 to listOf(VisibleAppWindow("game", 0, isFocused = true)),
                ),
                isInteractive = true,
                refreshRateFpsByDisplay = mapOf(0 to 120),
            ),
            assignments = listOf(automatic("game", 60)),
        )

        assertEquals(60, plan.effectiveAutoTuneTargetFps)
    }

    @Test
    fun `automatic runtime target falls back to configured target when refresh is unavailable`() {
        val plan = enabledPlan(
            snapshot = VisibleAppSnapshot(
                windowsByDisplay = mapOf(
                    0 to listOf(VisibleAppWindow("game", 0, isFocused = true)),
                ),
                isInteractive = true,
            ),
            assignments = listOf(automatic("game", 120)),
        )

        assertEquals(120, plan.effectiveAutoTuneTargetFps)
        assertNull(plan.foregroundDisplayRefreshRateFps)
    }

    @Test
    fun `display mode identity changes even when effective target remains capped`() {
        val assignment = automatic("game", 30)
        fun plan(refreshRateFps: Int) = enabledPlan(
            snapshot = VisibleAppSnapshot(
                windowsByDisplay = mapOf(
                    0 to listOf(VisibleAppWindow("game", 0, isFocused = true)),
                ),
                isInteractive = true,
                refreshRateFpsByDisplay = mapOf(0 to refreshRateFps),
            ),
            assignments = listOf(assignment),
        )

        val at60Hz = plan(60)
        val at120Hz = plan(120)

        assertEquals(30, at60Hz.effectiveAutoTuneTargetFps)
        assertEquals(30, at120Hz.effectiveAutoTuneTargetFps)
        assertNotEquals(at60Hz, at120Hz)
    }

    @Test
    fun `hosting display identity changes when app moves between displays`() {
        val assignment = automatic("game", 120)
        fun plan(displayId: Int) = enabledPlan(
            snapshot = VisibleAppSnapshot(
                windowsByDisplay = mapOf(
                    displayId to listOf(VisibleAppWindow("game", displayId, isFocused = true)),
                ),
                isInteractive = true,
                refreshRateFpsByDisplay = mapOf(displayId to 60),
            ),
            assignments = listOf(assignment),
        )

        val onDefaultDisplay = plan(0)
        val onExternalDisplay = plan(2)

        assertEquals(60, onDefaultDisplay.effectiveAutoTuneTargetFps)
        assertEquals(60, onExternalDisplay.effectiveAutoTuneTargetFps)
        assertNotEquals(onDefaultDisplay, onExternalDisplay)
    }

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

        val plan = enabledPlan(snapshot, listOf(automatic, static))

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

        val plan = enabledPlan(snapshot, listOf(automatic, static))

        assertEquals("reader", plan.foregroundPackageName)
        assertNull(plan.autoTuneAssignment)
        assertEquals(listOf(static), plan.staticAssignments)
    }

    @Test
    fun `screen off produces no automatic owner`() {
        val plan = enabledPlan(
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

        val plan = enabledPlan(snapshot, listOf(first, recent))

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

        val plan = enabledPlan(snapshot, listOf(recent, active))

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

        val plan = enabledPlan(snapshot, listOf(defaultDisplay, latest))

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

        val plan = enabledPlan(
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
