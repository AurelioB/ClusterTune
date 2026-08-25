package com.aure.clustertune.apps

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
    fun vendorAssistantOnlySnapshotRetainsLastRealWindowEvent() {
        val merged = mergeEventFallbackWindows(
            observed = mapOf(
                0 to listOf(
                    VisibleAppWindow(
                        "com.ayn.gameassistant",
                        0,
                        isFocused = true,
                        isActive = true,
                    ),
                ),
            ),
            eventFallbacks = mapOf(0 to "com.example.game"),
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertEquals(
            setOf("com.ayn.gameassistant", "com.example.game"),
            merged.getValue(0).mapTo(mutableSetOf()) { it.packageName },
        )
    }

    @Test
    fun unresolvedApplicationWindowDoesNotSynthesizeARealFallbackWindow() {
        val merged = mergeEventFallbackWindows(
            observed = mapOf(0 to emptyList()),
            eventFallbacks = mapOf(0 to "com.example.game"),
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertTrue(merged.getValue(0).isEmpty())
    }

    @Test
    fun sameWindowIdRetainsPackageAcrossTemporaryRootLoss() {
        val cache = AccessibilityWindowPackageCache()
        val identity = AccessibilityWindowIdentity(displayId = 0, windowId = 42)
        cache.record(identity, "com.example.game")

        assertEquals("com.example.game", cache.resolvePackage(identity, resolvedPackageName = null))
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
        val merged = mergeEventFallbackWindows(
            observed = mapOf(
                0 to listOf(
                    VisibleAppWindow("com.example.launcher", 0, isFocused = true, isActive = true),
                    VisibleAppWindow("com.rp.gameassistant", 0),
                ),
            ),
            eventFallbacks = mapOf(0 to "com.example.oldgame"),
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )

        assertEquals(
            setOf("com.example.launcher", "com.rp.gameassistant"),
            merged.getValue(0).mapTo(mutableSetOf()) { it.packageName },
        )
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
        val absent = mergeEventFallbackWindows(
            observed = emptyMap(),
            eventFallbacks = mapOf(0 to "com.example.closedgame"),
            obscuringPackages = VENDOR_GAME_ASSISTANT_PACKAGES,
        )
        val retainedDuringGrace = tracker.stabilize(absent, { true }, nowMs = 100)
        val expired = tracker.stabilize(absent, { true }, nowMs = 400)

        assertTrue(absent.isEmpty())
        assertEquals(
            "com.example.closedgame",
            retainedDuringGrace.windowsByDisplay.getValue(0).single().packageName,
        )
        assertTrue(expired.windowsByDisplay.isEmpty())
    }
}
