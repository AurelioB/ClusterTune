package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.root.host.HostAppWindow
import org.junit.Assert.*
import org.junit.Test

class PrivilegedAppSnapshotTest {
    private val primary = HostAppWindow("com.game.primary", 0, true, 100)
    private val secondary = HostAppWindow("com.game.secondary", 4, false, 90)
    private fun snapshot(
        tasks: List<HostAppWindow>, interactive: Boolean = true,
        displays: Map<Int, Int> = mapOf(0 to 120, 4 to 60),
    ) = privilegedAppSnapshot(tasks, interactive, displays,
        TRANSIENT_APP_WINDOW_PACKAGES + setOf("com.clustertune", "com.keyboard", "com.vendor.panel"))

    @Test fun `both displays contribute to static profiles while focus selects one Auto Tune owner`() {
        val result = snapshot(listOf(primary, secondary))
        assertEquals(setOf(primary.packageName, secondary.packageName), result.packages)
        val assignments = listOf(primary, secondary).map {
            AppProfileAssignment(packageName = it.packageName, appLabel = it.packageName,
                profileId = null, autoTuneTargetFps = 144)
        }
        val plan = resolveAppAutomationPlan(result, assignments, autoTuneEnabled = true)
        assertEquals(primary.packageName, plan.foregroundPackageName)
        assertEquals(0, plan.foregroundDisplayId)
        assertEquals(120, plan.effectiveAutoTuneTargetFps)
        val changed = snapshot(listOf(primary.copy(focused = false), secondary.copy(focused = true)))
        val changedPlan = resolveAppAutomationPlan(changed, assignments, autoTuneEnabled = true)
        assertEquals(4, changedPlan.foregroundDisplayId)
        assertEquals(60, changedPlan.effectiveAutoTuneTargetFps)
        assertEquals(result.packages, changed.packages)
    }

    @Test fun `split screen preserves every visible package and chooses the focused picker app`() {
        val other = primary.copy(packageName = "com.game.other", focused = false, lastActiveTime = 200)
        val result = snapshot(listOf(other, primary, secondary))
        assertEquals(2, result.windowsByDisplay.getValue(0).size)
        assertEquals(primary.packageName, result.pickerPackageByDisplay[0])
        assertEquals(primary.packageName, selectVisibleAppWindow(result)?.packageName)
    }

    @Test fun `same app on both displays keeps both display identities`() {
        val result = snapshot(listOf(primary, primary.copy(displayId = 4)))
        assertEquals(setOf(primary.packageName), result.packages)
        assertEquals(setOf(0, 4), result.windowsByDisplay.keys)
    }

    @Test fun `closed apps cannot survive in automation or picker evidence`() {
        snapshot(listOf(primary, secondary))
        val result = snapshot(listOf(secondary))
        assertEquals(setOf(secondary.packageName), result.packages)
        assertFalse(result.pickerPackageByDisplay.containsKey(0))
        assertEquals(secondary.packageName, result.mostRecentAppIdentity?.packageName)
    }

    @Test fun `sleeping and disconnected displays contribute nothing`() {
        assertEquals(VisibleAppSnapshot.Empty, snapshot(listOf(primary), interactive = false))
        val result = snapshot(listOf(primary, secondary), displays = mapOf(4 to 60))
        assertEquals(setOf(secondary.packageName), result.packages)
        assertFalse(result.recentPackageByDisplay.containsKey(0))
    }

    @Test fun `unknown refresh rate preserves the app and the configured Auto Tune target`() {
        val result = privilegedAppSnapshot(listOf(primary), true, emptyMap(), emptySet(), setOf(0))
        val plan = resolveAppAutomationPlan(result, listOf(AppProfileAssignment(primary.packageName, "Game",
            autoTuneTargetFps = 90)), autoTuneEnabled = true)
        assertEquals(primary.packageName, plan.foregroundPackageName)
        assertNull(plan.foregroundDisplayRefreshRateFps)
        assertEquals(90, plan.effectiveAutoTuneTargetFps)
    }

    @Test fun `system panels keyboard and our UI cannot become the tuning target`() {
        val tasks = listOf("com.android.systemui", "com.clustertune", "com.keyboard", "com.vendor.panel")
            .map { primary.copy(packageName = it, lastActiveTime = 1_000) } + primary
        assertEquals(setOf(primary.packageName), snapshot(tasks).packages)
    }

    @Test fun `permission activity in the games task preserves the game rather than the dialog`() {
        val dialog = primary.copy(packageName = "com.android.permissioncontroller", basePackageName = primary.packageName)
        val result = snapshot(listOf(dialog, secondary))
        assertEquals(setOf(primary.packageName, secondary.packageName), result.packages)
        assertEquals(primary.packageName, result.pickerPackageByDisplay[0])
        assertFalse(result.packages.contains(dialog.packageName))
    }

    @Test fun `ambiguous focus uses activity recency and is stable across enumeration orders`() {
        val tasks = listOf(primary, secondary.copy(focused = true, lastActiveTime = 200))
        assertEquals(snapshot(tasks), snapshot(tasks.reversed()))
        assertEquals(secondary.packageName, selectVisibleAppWindow(snapshot(tasks))?.packageName)
    }
}
