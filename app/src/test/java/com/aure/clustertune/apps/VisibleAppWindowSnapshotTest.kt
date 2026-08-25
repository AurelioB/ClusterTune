package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleAppWindowSnapshotTest {
    @Test
    fun fractionalDisplayRefreshRateIsRoundedToNominalFps() {
        assertEquals(60, nominalDisplayRefreshRateFps(59.94f))
        assertEquals(120, nominalDisplayRefreshRateFps(119.88f))
    }

    @Test
    fun invalidDisplayRefreshRatesAreUnavailable() {
        assertNull(nominalDisplayRefreshRateFps(0f))
        assertNull(nominalDisplayRefreshRateFps(-60f))
        assertNull(nominalDisplayRefreshRateFps(Float.NaN))
        assertNull(nominalDisplayRefreshRateFps(Float.POSITIVE_INFINITY))
    }

    @Test
    fun packagesIncludeApplicationsFromEveryDisplay() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("com.example.primary", 0, isFocused = true)),
                1 to listOf(VisibleAppWindow("com.example.secondary", 1, isActive = true)),
            ),
            isInteractive = true,
        )

        assertEquals(
            setOf("com.example.primary", "com.example.secondary"),
            snapshot.packages,
        )
    }

    @Test
    fun packageSetIsUnaffectedByFocusChanges() {
        val focusedPrimary = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0, isFocused = true),
                    VisibleAppWindow("com.example.second", 0),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
            isInteractive = true,
        )
        val focusedSecondary = focusedPrimary.copy(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0),
                    VisibleAppWindow("com.example.second", 0, isFocused = true),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
        )

        assertEquals(focusedPrimary.packages, focusedSecondary.packages)
    }

    @Test
    fun resolverContributorsDoNotChangeWhenOnlyFocusChanges() {
        val before = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0, isFocused = true),
                    VisibleAppWindow("com.example.second", 0),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
            isInteractive = true,
        )
        val after = before.copy(
            windowsByDisplay = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.first", 0),
                    VisibleAppWindow("com.example.second", 0, isFocused = true),
                ),
                1 to listOf(VisibleAppWindow("com.example.third", 1)),
            ),
        )

        fun resolve(snapshot: VisibleAppSnapshot) = CombinedAppProfileResolver.resolve(
            snapshot.packages.map { packageName ->
                VisibleAppProfileTarget(packageName = packageName, profileId = packageName)
            },
        ).contributors.map { it.packageName }

        assertEquals(resolve(before), resolve(after))
    }

    @Test
    fun duplicateWindowsRemainPresentInSnapshotButPackageSetDeduplicates() {
        val snapshot = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
                1 to listOf(VisibleAppWindow("com.example.game", 1, isActive = true)),
            ),
            isInteractive = true,
        )

        assertEquals(2, snapshot.windowsByDisplay.values.flatten().size)
        assertEquals(setOf("com.example.game"), snapshot.packages)
    }

    @Test
    fun emptySnapshotHasNoContributors() {
        assertTrue(VisibleAppSnapshot.Empty.packages.isEmpty())
    }

    @Test
    fun pickerHandoffIsFixedExpiryMetadataAndNeverAnAutomationWindow() {
        val tracker = PickerForegroundAppHandoffTracker(
            seedDurationMs = 2_000L,
            leaseDurationMs = 30_000L,
        )
        assertTrue(
            tracker.update(
                verifiedPackageByDisplay = mapOf(0 to "com.example.game"),
                displayOn = { true },
                nowMs = 0L,
            ).isEmpty(),
        )

        val firstMissing = tracker.update(emptyMap(), { true }, nowMs = 100L)
        val repeatedMissing = tracker.update(emptyMap(), { true }, nowMs = 1_500L)
        val handoff = firstMissing.getValue(0)
        val handoffOnlySnapshot = VisibleAppSnapshot(
            windowsByDisplay = emptyMap(),
            isInteractive = true,
            refreshRateFpsByDisplay = mapOf(0 to 60),
            pickerHandoffByDisplay = firstMissing,
        )
        val automationPlan = resolveAppAutomationPlan(
            snapshot = handoffOnlySnapshot,
            assignments = listOf(
                AppProfileAssignment(
                    packageName = "com.example.game",
                    appLabel = "Game",
                    autoTuneTargetFps = 60,
                ),
            ),
        )

        assertEquals("com.example.game", handoff.packageName)
        assertEquals(2_100L, handoff.seedExpiresAtUptimeMs)
        assertEquals(30_100L, handoff.leaseExpiresAtUptimeMs)
        assertEquals(firstMissing, repeatedMissing)
        assertTrue(handoffOnlySnapshot.windowsByDisplay.isEmpty())
        assertTrue(handoffOnlySnapshot.packages.isEmpty())
        assertNull(automationPlan.foregroundPackageName)
        assertNull(automationPlan.autoTuneAssignment)
        assertTrue(tracker.update(emptyMap(), { true }, nowMs = 30_100L).isEmpty())
        assertTrue(tracker.update(emptyMap(), { true }, nowMs = 30_101L).isEmpty())
    }

    @Test
    fun pickerHandoffIsClearedByRealReplacementDisplayOffAndTeardown() {
        val tracker = PickerForegroundAppHandoffTracker(
            seedDurationMs = 2_000L,
            leaseDurationMs = 30_000L,
        )
        tracker.update(mapOf(0 to "com.example.game"), { true }, nowMs = 0L)
        assertEquals(
            "com.example.game",
            tracker.update(emptyMap(), { true }, nowMs = 100L).getValue(0).packageName,
        )

        assertTrue(
            tracker.update(
                verifiedPackageByDisplay = mapOf(0 to "com.example.launcher"),
                displayOn = { true },
                nowMs = 200L,
            ).isEmpty(),
        )
        assertEquals(
            "com.example.launcher",
            tracker.update(emptyMap(), { true }, nowMs = 300L).getValue(0).packageName,
        )
        assertTrue(tracker.update(emptyMap(), { false }, nowMs = 400L).isEmpty())
        assertTrue(tracker.update(emptyMap(), { true }, nowMs = 500L).isEmpty())

        tracker.update(mapOf(0 to "com.example.game"), { true }, nowMs = 600L)
        tracker.clear()
        assertTrue(tracker.update(emptyMap(), { true }, nowMs = 700L).isEmpty())
    }

    @Test
    fun rootlessExactWindowMaintainsProvenanceUntilItsIdentityDisappears() {
        val cache = AccessibilityWindowPackageCache()
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        val tracker = PickerForegroundAppHandoffTracker(
            seedDurationMs = 2_000L,
            leaseDurationMs = 30_000L,
        )
        cache.record(identity, "com.example.game")
        tracker.update(mapOf(0 to "com.example.game"), { true }, nowMs = 0L)

        val rootlessPackage = cache.resolvePackage(identity, resolvedPackageName = null)
        val firstRootless = tracker.update(
            verifiedPackageByDisplay = mapOf(0 to requireNotNull(rootlessPackage)),
            displayOn = { true },
            nowMs = 100L,
        )
        val repeatedRootless = tracker.update(
            verifiedPackageByDisplay = mapOf(0 to requireNotNull(rootlessPackage)),
            displayOn = { true },
            nowMs = 2_100L,
        )
        cache.retainOnly(emptySet())
        val firstMissing = tracker.update(emptyMap(), { true }, nowMs = 3_000L)
        val repeatedMissing = tracker.update(emptyMap(), { true }, nowMs = 5_000L)

        assertEquals("com.example.game", rootlessPackage)
        assertTrue(firstRootless.isEmpty())
        assertTrue(repeatedRootless.isEmpty())
        assertNull(cache.resolvePackage(identity, resolvedPackageName = null))
        assertEquals(firstMissing, repeatedMissing)
        assertEquals(5_000L, firstMissing.getValue(0).seedExpiresAtUptimeMs)
        assertEquals(33_000L, firstMissing.getValue(0).leaseExpiresAtUptimeMs)
    }

    @Test
    fun repeatedVendorAssistantOnlySnapshotsExpireThePriorGameAfterOneGracePeriod() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        val assistantOnly = mapOf(
            0 to listOf(
                VisibleAppWindow(
                    "com.ayn.gameassistant",
                    0,
                    isFocused = true,
                    isActive = true,
                ),
            ),
        )
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        val firstAssistantOnly = tracker.stabilize(
            observed = assistantOnly,
            displayOn = { true },
            nowMs = 100,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )
        val repeatedAssistantOnly = tracker.stabilize(
            observed = assistantOnly,
            displayOn = { true },
            nowMs = 250,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )
        val expired = tracker.stabilize(
            observed = assistantOnly,
            displayOn = { true },
            nowMs = 400,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertEquals(
            setOf("com.ayn.gameassistant", "com.example.game"),
            firstAssistantOnly.windowsByDisplay.getValue(0).mapTo(mutableSetOf()) { it.packageName },
        )
        assertEquals(400L, firstAssistantOnly.nextDeadlineMs)
        assertEquals(
            setOf("com.ayn.gameassistant", "com.example.game"),
            repeatedAssistantOnly.windowsByDisplay
                .getValue(0)
                .mapTo(mutableSetOf()) { it.packageName },
        )
        assertEquals(400L, repeatedAssistantOnly.nextDeadlineMs)
        assertEquals(
            setOf("com.ayn.gameassistant"),
            expired.windowsByDisplay.getValue(0).mapTo(mutableSetOf()) { it.packageName },
        )
        assertNull(expired.nextDeadlineMs)
    }

    @Test
    fun sameWindowIdRetainsPackageAcrossTemporaryRootLoss() {
        val cache = AccessibilityWindowPackageCache()
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        cache.record(identity, "com.example.game")
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
        )

        val rootlessPackage = requireNotNull(
            cache.resolvePackage(identity, resolvedPackageName = null),
        )
        val stillObserved = tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow(rootlessPackage, 0)),
            ),
            displayOn = { true },
            nowMs = 1_000,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertEquals("com.example.game", rootlessPackage)
        assertEquals("com.example.game", stillObserved.windowsByDisplay.getValue(0).single().packageName)
        assertNull(stillObserved.nextDeadlineMs)
    }

    @Test
    fun excludedPackageReuseInvalidatesTheOldWindowIdentity() {
        val cache = AccessibilityWindowPackageCache()
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        cache.record(identity, "com.example.game")

        cache.remove(identity)

        assertNull(cache.resolvePackage(identity, resolvedPackageName = null))
    }

    @Test
    fun differentRootlessWindowIdDoesNotInheritAndExpiresAfterGrace() {
        val cache = AccessibilityWindowPackageCache()
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 500)
        val oldIdentity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        val newIdentity = AccessibilityWindowIdentity(displayId = 0, windowId = 43)
        cache.record(oldIdentity, "com.example.game")
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
        )

        cache.retainOnly(setOf(newIdentity))
        val rootlessPackage = cache.resolvePackage(newIdentity, resolvedPackageName = null)
        val retainedDuringGrace = tracker.stabilize(emptyMap(), { true }, nowMs = 100)
        val expired = tracker.stabilize(emptyMap(), { true }, nowMs = 600)

        assertNull(rootlessPackage)
        assertEquals(
            "com.example.game",
            retainedDuringGrace.windowsByDisplay.getValue(0).single().packageName,
        )
        assertTrue(expired.windowsByDisplay.isEmpty())
    }

    @Test
    fun identicalWindowIdsOnDifferentDisplaysAreIsolated() {
        val cache = AccessibilityWindowPackageCache()
        val primaryIdentity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        val secondaryIdentity = AccessibilityWindowIdentity(displayId = 1, windowId = 42)
        cache.record(primaryIdentity, "com.example.primary")
        cache.record(secondaryIdentity, "com.example.secondary")

        assertEquals("com.example.primary", cache.resolvePackage(primaryIdentity, null))
        assertEquals("com.example.secondary", cache.resolvePackage(secondaryIdentity, null))
    }

    @Test
    fun removedWindowIdIsForgottenAndItsPublishedAppExpires() {
        val cache = AccessibilityWindowPackageCache()
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 500)
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        cache.record(identity, "com.example.game")
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.game", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
        )

        cache.retainOnly(emptySet())
        val retainedDuringGrace = tracker.stabilize(emptyMap(), { true }, nowMs = 100)
        val expired = tracker.stabilize(emptyMap(), { true }, nowMs = 600)

        assertNull(cache.resolvePackage(identity, resolvedPackageName = null))
        assertEquals(
            "com.example.game",
            retainedDuringGrace.windowsByDisplay.getValue(0).single().packageName,
        )
        assertTrue(expired.windowsByDisplay.isEmpty())
    }

    @Test
    fun observedFallbackSelectionIsIndependentOfEnumerationOrder() {
        val alpha = VisibleAppWindow("com.example.alpha", 0, isActive = true)
        val zeta = VisibleAppWindow("com.example.zeta", 0, isActive = true)

        assertNull(selectObservedFallbackPackage(emptyList(), null))
        assertEquals("com.example.alpha", selectObservedFallbackPackage(listOf(zeta, alpha), null))
        assertEquals("com.example.alpha", selectObservedFallbackPackage(listOf(alpha, zeta), null))
    }

    @Test
    fun observedFallbackSelectionUsesFocusActivityAndCachedRecencyInOrder() {
        val focused = VisibleAppWindow("com.example.focused", 0, isFocused = true)
        val active = VisibleAppWindow("com.example.active", 0, isActive = true)
        val cached = VisibleAppWindow("com.example.cached", 0)
        val other = VisibleAppWindow("com.example.other", 0)

        assertEquals(
            "com.example.focused",
            selectObservedFallbackPackage(listOf(cached, active, focused), "com.example.cached"),
        )
        assertEquals(
            "com.example.active",
            selectObservedFallbackPackage(listOf(cached, active), "com.example.cached"),
        )
        assertEquals(
            "com.example.cached",
            selectObservedFallbackPackage(listOf(other, cached), "com.example.cached"),
        )
    }

    @Test
    fun realObservedWindowWinsWithoutAddingStaleFallback() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.oldgame", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
        )
        val replacement = tracker.stabilize(
            observed = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.launcher", 0, isFocused = true, isActive = true),
                    VisibleAppWindow("com.rp.gameassistant", 0),
                ),
            ),
            displayOn = { true },
            nowMs = 100,
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertEquals(
            setOf("com.example.launcher", "com.rp.gameassistant"),
            replacement.windowsByDisplay.getValue(0).mapTo(mutableSetOf()) { it.packageName },
        )
        assertNull(replacement.nextDeadlineMs)
    }

    @Test
    fun absentDisplayAllowsStaleEventFallbackToExpire() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300)
        tracker.stabilize(
            observed = mapOf(
                0 to listOf(VisibleAppWindow("com.example.closedgame", 0, isFocused = true)),
            ),
            displayOn = { true },
            nowMs = 0,
        )
        val retainedDuringGrace = tracker.stabilize(emptyMap(), { true }, nowMs = 100)
        val expired = tracker.stabilize(emptyMap(), { true }, nowMs = 400)

        assertEquals(
            "com.example.closedgame",
            retainedDuringGrace.windowsByDisplay.getValue(0).single().packageName,
        )
        assertTrue(expired.windowsByDisplay.isEmpty())
    }
}
