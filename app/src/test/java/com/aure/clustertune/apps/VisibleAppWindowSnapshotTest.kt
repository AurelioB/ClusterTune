package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleAppWindowSnapshotTest {
    @Test
    fun explicitShadePreservesFocusedGameAcrossDisplaysUntilDismissed() {
        val tracker = VisibleWindowDisappearanceTracker(500L)
        val game = VisibleAppWindow("game", 0, isFocused = true, isActive = true)
        val other = VisibleAppWindow("other", 4, isFocused = true)
        tracker.stabilize(mapOf(0 to listOf(game), 4 to listOf(other)), { true }, 0L)

        val covered = tracker.stabilize(
            mapOf(4 to listOf(other)), { true }, 10_000L,
            transientlyCoveredDisplays = setOf(0),
        )
        assertEquals(game, selectVisibleAppWindow(VisibleAppSnapshot(covered.windowsByDisplay, true)))
        assertNull(covered.nextDeadlineMs)

        val launcher = VisibleAppWindow("launcher", 0, isFocused = true, isActive = true)
        val dismissed = tracker.stabilize(
            mapOf(0 to listOf(launcher), 4 to listOf(other)), { true }, 11_000L,
            transientlyCoveredDisplays = setOf(0),
        )
        assertEquals(listOf(launcher), dismissed.windowsByDisplay[0])
    }

    @Test
    fun unknownAbsenceAfterShadeDismissalStillExpires() {
        val tracker = VisibleWindowDisappearanceTracker(500L)
        tracker.stabilize(mapOf(0 to listOf(VisibleAppWindow("game", 0))), { true }, 0L)
        tracker.stabilize(emptyMap(), { true }, 1_000L, transientlyCoveredDisplays = setOf(0))
        assertEquals(2_500L, tracker.stabilize(emptyMap(), { true }, 2_000L).nextDeadlineMs)
        assertTrue(tracker.stabilize(emptyMap(), { true }, 2_500L).windowsByDisplay.isEmpty())
    }

    @Test
    fun persistentSystemWindowsAndHudDoNotPreserveOwnership() {
        assertTrue(isForegroundAppObscuringWindow(null, "com.android.systemui", true, true))
        assertTrue(isForegroundAppObscuringWindow("ClusterTune overlay", null, true, true))
        assertEquals(false, isForegroundAppObscuringWindow(null, "com.android.systemui", false, false))
        assertEquals(false, isForegroundAppObscuringWindow("ClusterTune performance HUD", null, false, false))
    }

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
    fun pickerEvidenceNeverBecomesAnAutomationWindow() {
        val tracker = PickerForegroundPackageTracker()
        tracker.recordWindowStateEvent(0, "com.example.game", nowMs = 0L)
        val evidence = tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 100L)
        val repeatedEmpty = tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 1_000L)
        val evidenceOnlySnapshot = VisibleAppSnapshot(
            windowsByDisplay = emptyMap(),
            isInteractive = true,
            refreshRateFpsByDisplay = mapOf(0 to 60),
            pickerPackageByDisplay = evidence,
        )
        val automationPlan = resolveAppAutomationPlan(
            autoTuneEnabled = true,
            snapshot = evidenceOnlySnapshot,
            assignments = listOf(
                AppProfileAssignment(
                    packageName = "com.example.game",
                    appLabel = "Game",
                    autoTuneTargetFps = 60,
                ),
            ),
        )

        assertEquals("com.example.game", evidence.getValue(0))
        assertEquals(evidence, repeatedEmpty)
        assertTrue(evidenceOnlySnapshot.windowsByDisplay.isEmpty())
        assertTrue(evidenceOnlySnapshot.packages.isEmpty())
        assertNull(automationPlan.foregroundPackageName)
        assertNull(automationPlan.autoTuneAssignment)
    }

    @Test
    fun displayQualifiedWindowEventSeedsPickerWhenOemPublishesNoApplicationWindow() {
        val tracker = PickerForegroundPackageTracker()

        tracker.recordWindowStateEvent(
            displayId = 0,
            packageName = "com.example.game",
            nowMs = 0L,
        )
        val firstEmptySnapshot = tracker.updateFromEnumeration(
            verifiedPackageByDisplay = emptyMap(),
            displayOn = { true },
            nowMs = 100L,
        )
        val repeatedEmptySnapshot = tracker.updateFromEnumeration(
            verifiedPackageByDisplay = emptyMap(),
            displayOn = { true },
            nowMs = 20_000L,
        )

        assertEquals("com.example.game", firstEmptySnapshot.getValue(0))
        assertEquals(firstEmptySnapshot, repeatedEmptySnapshot)
    }

    @Test
    fun newerDisplayQualifiedEventReplacesPickerEvidenceWithoutCrossingDisplays() {
        val tracker = PickerForegroundPackageTracker()
        tracker.recordWindowStateEvent(0, "com.example.old", nowMs = 0L)
        tracker.recordWindowStateEvent(1, "com.example.secondary", nowMs = 0L)

        tracker.recordWindowStateEvent(0, "com.example.new", nowMs = 100L)
        val evidence = tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 200L)

        assertEquals("com.example.new", evidence.getValue(0))
        assertEquals("com.example.secondary", evidence.getValue(1))
    }

    @Test
    fun staleEnumerationCannotOverwriteANewerWindowEvent() {
        val tracker = PickerForegroundPackageTracker()
        tracker.recordWindowStateEvent(0, "com.example.old", nowMs = 0L)
        tracker.recordWindowStateEvent(0, "com.example.new", nowMs = 100L)
        val afterStaleEnumeration = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.old"),
            { true },
            nowMs = 150L,
        )
        val afterLastScheduledStaleEnumeration = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.old"),
            { true },
            nowMs = 750L,
        )
        val afterProtection = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.new"),
            { true },
            nowMs = 1_000L,
        )

        assertEquals("com.example.new", afterStaleEnumeration.getValue(0))
        assertEquals(afterStaleEnumeration, afterLastScheduledStaleEnumeration)
        assertEquals(afterStaleEnumeration, afterProtection)
    }

    @Test
    fun oldEventCanBeReplacedByRealEnumerationAfterTheProtectionWindow() {
        val tracker = PickerForegroundPackageTracker()
        tracker.recordWindowStateEvent(0, "com.example.event", nowMs = 0L)

        val protected = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.real"),
            { true },
            nowMs = 999L,
        )
        val replaced = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.real"),
            { true },
            nowMs = 1_000L,
        )

        assertEquals("com.example.event", protected.getValue(0))
        assertEquals("com.example.real", replaced.getValue(0))
    }

    @Test
    fun enumerationIsUsedAsColdStartFallbackUntilAWindowEventArrives() {
        val tracker = PickerForegroundPackageTracker()
        val firstEnumeration = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.launcher"),
            { true },
            nowMs = 0L,
        )
        val secondEnumeration = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.game"),
            { true },
            nowMs = 100L,
        )
        tracker.recordWindowStateEvent(0, "com.example.event", nowMs = 200L)
        val afterEvent = tracker.updateFromEnumeration(
            mapOf(0 to "com.example.stale"),
            { true },
            nowMs = 300L,
        )

        assertEquals("com.example.launcher", firstEnumeration.getValue(0))
        assertEquals("com.example.game", secondEnumeration.getValue(0))
        assertEquals("com.example.event", afterEvent.getValue(0))
    }

    @Test
    fun pickerEvidenceIsClearedByDisplayOffAndTeardown() {
        val tracker = PickerForegroundPackageTracker()
        tracker.recordWindowStateEvent(0, "com.example.game", nowMs = 0L)
        assertEquals(
            "com.example.game",
            tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 100L)
                .getValue(0),
        )
        assertTrue(tracker.updateFromEnumeration(emptyMap(), { false }, nowMs = 200L).isEmpty())
        assertTrue(tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 300L).isEmpty())

        tracker.recordWindowStateEvent(0, "com.example.game", nowMs = 400L)
        tracker.clear()
        assertTrue(tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 500L).isEmpty())
    }

    @Test
    fun rootlessExactWindowMaintainsProvenanceUntilItsIdentityDisappears() {
        val cache = AccessibilityWindowPackageCache()
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        val tracker = PickerForegroundPackageTracker()
        cache.record(identity, "com.example.game")
        tracker.updateFromEnumeration(
            mapOf(0 to "com.example.game"),
            { true },
            nowMs = 0L,
        )

        val rootlessPackage = cache.resolvePackage(identity, resolvedPackageName = null)
        val firstRootless = tracker.updateFromEnumeration(
            verifiedPackageByDisplay = mapOf(0 to requireNotNull(rootlessPackage)),
            displayOn = { true },
            nowMs = 100L,
        )
        cache.retainOnly(emptySet())
        val missing = tracker.updateFromEnumeration(emptyMap(), { true }, nowMs = 200L)

        assertEquals("com.example.game", rootlessPackage)
        assertEquals("com.example.game", firstRootless.getValue(0))
        assertNull(cache.resolvePackage(identity, resolvedPackageName = null))
        assertEquals(firstRootless, missing)
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
    fun offDisplayCannotPublishOrRetainAStaleApplicationWindow() {
        val tracker = VisibleWindowDisappearanceTracker(graceMs = 300L)
        val game = mapOf(
            4 to listOf(VisibleAppWindow("com.example.game", 4, isFocused = true)),
        )

        val freshWhileOff = tracker.stabilize(game, displayOn = { false }, nowMs = 0L)
        tracker.stabilize(game, displayOn = { true }, nowMs = 100L)
        val staleAfterOff = tracker.stabilize(game, displayOn = { false }, nowMs = 200L)

        assertTrue(freshWhileOff.windowsByDisplay.isEmpty())
        assertTrue(staleAfterOff.windowsByDisplay.isEmpty())
        assertNull(staleAfterOff.nextDeadlineMs)
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
