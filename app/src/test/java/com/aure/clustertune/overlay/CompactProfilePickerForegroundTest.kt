package com.aure.clustertune.overlay

import com.aure.clustertune.apps.ForegroundAppInfo
import com.aure.clustertune.apps.PickerForegroundAppHandoff
import com.aure.clustertune.apps.VENDOR_GAME_ASSISTANT_PACKAGES
import com.aure.clustertune.apps.VisibleAppSnapshot
import com.aure.clustertune.apps.VisibleAppWindow
import com.aure.clustertune.apps.selectVisibleAppWindow
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.ui.CompactOverlayMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProfilePickerForegroundTest {
    @Test
    fun profilePicker_opensAutoTuneForForegroundAutoAssignment() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.AUTO_TUNE,
            initialCompactOverlayMode(CompactOverlayMode.PROFILES, "com.game", assignments),
        )
        assertEquals(
            CompactOverlayMode.TUNER,
            initialCompactOverlayMode(CompactOverlayMode.TUNER, "com.game", assignments),
        )
        assertEquals(
            CompactOverlayMode.PROFILES,
            initialCompactOverlayMode(CompactOverlayMode.PROFILES, "com.other", assignments),
        )
    }

    @Test
    fun correctedForegroundOpensAutoTuneUntilUserSelectsATab() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.AUTO_TUNE,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.PROFILES,
                currentMode = CompactOverlayMode.PROFILES,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = false,
            ),
        )
        assertEquals(
            CompactOverlayMode.TUNER,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.PROFILES,
                currentMode = CompactOverlayMode.TUNER,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = true,
            ),
        )
    }

    @Test
    fun requestedTunerModeIsNotAutomaticallyChangedForAutoAssignment() {
        val assignments = listOf(
            AppProfileAssignment(
                packageName = "com.game",
                appLabel = "Game",
                autoTuneTargetFps = 60,
            ),
        )

        assertEquals(
            CompactOverlayMode.TUNER,
            correctedCompactOverlayMode(
                requestedMode = CompactOverlayMode.TUNER,
                currentMode = CompactOverlayMode.TUNER,
                foregroundPackageName = "com.game",
                assignments = assignments,
                modeChangedByUser = false,
            ),
        )
    }

    @Test
    fun foregroundPickerRequiresATargetWhileGlobalTunerDoesNot() {
        assertTrue(compactProfilePickerRequiresForeground(CompactOverlayMode.PROFILES))
        assertFalse(compactProfilePickerRequiresForeground(CompactOverlayMode.TUNER))
        assertFalse(compactProfilePickerRequiresForeground(CompactOverlayMode.AUTO_TUNE))
    }

    @Test
    fun foregroundTargetLossDismissesEvenAfterUserChangesTabs() {
        val game = app("com.game")

        assertTrue(
            shouldDismissCompactProfilePickerAfterTargetLoss(
                requestedMode = CompactOverlayMode.PROFILES,
                previousForeground = game,
                updatedForeground = null,
            ),
        )
        assertFalse(
            shouldDismissCompactProfilePickerAfterTargetLoss(
                requestedMode = CompactOverlayMode.TUNER,
                previousForeground = game,
                updatedForeground = null,
            ),
        )
        assertFalse(
            shouldDismissCompactProfilePickerAfterTargetLoss(
                requestedMode = CompactOverlayMode.PROFILES,
                previousForeground = null,
                updatedForeground = null,
            ),
        )
    }

    private val ownPackage = "com.aure.clustertune"
    private val ignored = compactProfilePickerExcludedPackages(ownPackage)
    private val leaseDurationMs = 30_000L
    private fun app(packageName: String, label: String = packageName) =
        ForegroundAppInfo(packageName, label)
    private fun updateFromSnapshot(
        current: CompactProfilePickerForegroundLease,
        snapshot: VisibleAppSnapshot,
        nowUptimeMs: Long,
        pickerShowing: Boolean = true,
    ): CompactProfilePickerForegroundLease {
        val observation = observeCompactProfilePickerSnapshot(
            snapshot = snapshot,
            targetDisplayId = 0,
            excludedPackages = ignored,
            pickerShowing = pickerShowing,
            preferredPackageName = current.foreground?.packageName,
        )
        val detected = observation.verifiedPackageName?.let(::app)
        return updateCompactProfilePickerForeground(
            current = current,
            detected = detected,
            ignoredPackages = ignored,
            hasFilteredVisibleWindow = observation.isObscured,
            nowUptimeMs = nowUptimeMs,
            leaseDurationMs = leaseDurationMs,
        )
    }

    private fun snapshotOf(vararg windows: VisibleAppWindow) = VisibleAppSnapshot(
        windowsByDisplay = mapOf(0 to windows.toList()),
        isInteractive = true,
        refreshRateFpsByDisplay = mapOf(0 to 60),
    )

    private fun emptyAccessibilitySnapshot(
        isInteractive: Boolean = true,
        displayExists: Boolean = true,
        handoff: PickerForegroundAppHandoff? = null,
    ) = VisibleAppSnapshot(
        windowsByDisplay = emptyMap(),
        isInteractive = isInteractive,
        refreshRateFpsByDisplay = if (displayExists) mapOf(0 to 60) else emptyMap(),
        pickerHandoffByDisplay = handoff?.let { mapOf(0 to it) }.orEmpty(),
    )

    @Test
    fun unresolvedWithoutExactWindowIdentityClearsEstablishedContext() {
        assertNull(
            updateCompactProfilePickerForeground(
                current = CompactProfilePickerForegroundLease(app("com.game"), 30_000L),
                detected = null,
                ignoredPackages = ignored,
                nowUptimeMs = 100L,
                leaseDurationMs = leaseDurationMs,
            ).foreground,
        )
    }

    @Test
    fun initialResolverFilteredNullLeavesContextEmpty() {
        val snapshot = snapshotOf(
            VisibleAppWindow(ownPackage, 0, isFocused = true),
        )

        assertNull(
            updateFromSnapshot(
                current = CompactProfilePickerForegroundLease(null, null),
                snapshot = snapshot,
                nowUptimeMs = 0L,
            ).foreground,
        )
    }

    @Test
    fun quickSettingsBridgeWaitsForGameBeforeOwnOverlayTakesFocus() = runTest {
        val systemUi = snapshotOf(
            VisibleAppWindow(SYSTEM_UI_PACKAGE, 0, isFocused = true, isActive = true),
        )
        val dolphin = snapshotOf(
            VisibleAppWindow("org.dolphinemu.dolphinemu", 0, isFocused = true, isActive = true),
        )
        val ownOverlay = snapshotOf(
            VisibleAppWindow(ownPackage, 0, isFocused = true, isActive = true),
        )
        val snapshots = MutableStateFlow(systemUi)
        val awaitingTarget = async(start = CoroutineStart.UNDISPATCHED) {
            awaitCompactProfilePickerTargetSnapshot(
                snapshots = snapshots,
                targetDisplayId = 0,
                excludedPackages = ignored,
                timeoutMs = 1_000L,
                nowUptimeMs = { 0L },
            )
        }

        snapshots.value = dolphin
        val targetSnapshot = requireNotNull(awaitingTarget.await()).snapshot
        val established = updateFromSnapshot(
            current = CompactProfilePickerForegroundLease(null, null),
            snapshot = targetSnapshot,
            nowUptimeMs = 100L,
        )
        val afterOwnOverlay = updateFromSnapshot(established, ownOverlay, nowUptimeMs = 200L)

        assertEquals("org.dolphinemu.dolphinemu", established.foreground?.packageName)
        assertEquals(established, afterOwnOverlay)
    }

    @Test
    fun quickSettingsBridgeTimeoutDoesNotInventAnInitialTarget() = runTest {
        val snapshots = MutableStateFlow(
            snapshotOf(
                VisibleAppWindow(SYSTEM_UI_PACKAGE, 0, isFocused = true, isActive = true),
            ),
        )

        val target = awaitCompactProfilePickerTargetSnapshot(
            snapshots = snapshots,
            targetDisplayId = 0,
            excludedPackages = ignored,
            timeoutMs = 1_000L,
            nowUptimeMs = { 0L },
        )

        assertNull(target)
    }

    @Test
    fun repeatedFilteredWindowsDoNotExtendAndEventuallyClearThePickerLease() {
        val game = snapshotOf(VisibleAppWindow("com.game", 0, isFocused = true))
        val established = updateFromSnapshot(
            current = CompactProfilePickerForegroundLease(null, null),
            snapshot = game,
            nowUptimeMs = 0L,
        )
        val filteredPackages = listOf(
            SYSTEM_UI_PACKAGE,
            ownPackage,
            VENDOR_GAME_ASSISTANT_PACKAGES.first(),
        )

        var retained = established
        filteredPackages.forEachIndexed { index, filteredPackage ->
            retained = updateFromSnapshot(
                current = retained,
                snapshot = snapshotOf(VisibleAppWindow(filteredPackage, 0, isFocused = true)),
                nowUptimeMs = (index + 1) * 5_000L,
            )
            assertEquals(established, retained)
        }

        val expired = updateFromSnapshot(
            current = retained,
            snapshot = snapshotOf(VisibleAppWindow(ownPackage, 0, isFocused = true)),
            nowUptimeMs = leaseDurationMs,
        )

        assertNull(expired.foreground)
        assertNull(expired.expiresAtUptimeMs)
    }

    @Test
    fun typeSystemOnlyQsAndOverlaySnapshotsSeedThenRetainDolphinWithoutRenewing() = runTest {
        val handoff = PickerForegroundAppHandoff(
            packageName = "org.dolphinemu.dolphinemu",
            seedExpiresAtUptimeMs = 2_000L,
            leaseExpiresAtUptimeMs = 30_000L,
        )
        val qsSnapshot = emptyAccessibilitySnapshot(handoff = handoff)
        val snapshots = MutableStateFlow(qsSnapshot)

        val target = requireNotNull(
            awaitCompactProfilePickerTargetSnapshot(
                snapshots = snapshots,
                targetDisplayId = 0,
                excludedPackages = ignored,
                timeoutMs = 1_000L,
                nowUptimeMs = { 100L },
            ),
        )
        val selected = requireNotNull(
            selectVisibleAppWindow(target.snapshot, targetDisplayId = 0, excludedPackages = ignored),
        )
        val established = updateCompactProfilePickerForeground(
            current = CompactProfilePickerForegroundLease(null, null),
            detected = app(selected.packageName),
            ignoredPackages = ignored,
            nowUptimeMs = 100L,
            leaseDurationMs = leaseDurationMs,
            detectedLeaseExpiresAtUptimeMs = target.leaseExpiresAtUptimeMs,
        )
        val overlayOnlySnapshot = emptyAccessibilitySnapshot(handoff = handoff)
        val retained = updateFromSnapshot(established, overlayOnlySnapshot, nowUptimeMs = 15_000L)
        val expired = updateFromSnapshot(retained, overlayOnlySnapshot, nowUptimeMs = 30_000L)

        assertTrue(qsSnapshot.windowsByDisplay.isEmpty())
        assertTrue(qsSnapshot.packages.isEmpty())
        assertEquals("org.dolphinemu.dolphinemu", selected.packageName)
        assertEquals(30_000L, established.expiresAtUptimeMs)
        assertEquals(established, retained)
        assertNull(expired.foreground)
        assertNull(expired.expiresAtUptimeMs)
    }

    @Test
    fun sourceHandoffCapsDisappearanceRetainedWindowAndCannotSeedANewSessionWhenStale() {
        val handoff = PickerForegroundAppHandoff(
            packageName = "org.dolphinemu.dolphinemu",
            seedExpiresAtUptimeMs = 2_000L,
            leaseExpiresAtUptimeMs = 30_000L,
        )
        val disappearanceRetained = VisibleAppSnapshot(
            windowsByDisplay = mapOf(
                0 to listOf(VisibleAppWindow(handoff.packageName, 0, isActive = false)),
            ),
            isInteractive = true,
            refreshRateFpsByDisplay = mapOf(0 to 60),
            pickerHandoffByDisplay = mapOf(0 to handoff),
        )

        val freshTarget = requireNotNull(
            compactProfilePickerTargetSnapshot(
                snapshot = disappearanceRetained,
                targetDisplayId = 0,
                excludedPackages = ignored,
                nowUptimeMs = 100L,
            ),
        )
        val staleTarget = compactProfilePickerTargetSnapshot(
            snapshot = disappearanceRetained,
            targetDisplayId = 0,
            excludedPackages = ignored,
            nowUptimeMs = 2_000L,
        )

        assertEquals(30_000L, freshTarget.leaseExpiresAtUptimeMs)
        assertNull(staleTarget)
    }

    @Test
    fun emptySnapshotRetentionRequiresShowingInteractivePickerOnExistingDisplay() {
        val established = CompactProfilePickerForegroundLease(app("com.game"), 30_000L)

        assertEquals(
            established,
            updateFromSnapshot(established, emptyAccessibilitySnapshot(), nowUptimeMs = 100L),
        )
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(),
                nowUptimeMs = 100L,
                pickerShowing = false,
            ).foreground,
        )
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(isInteractive = false),
                nowUptimeMs = 100L,
            ).foreground,
        )
        assertNull(
            updateFromSnapshot(
                established,
                emptyAccessibilitySnapshot(displayExists = false),
                nowUptimeMs = 100L,
            ).foreground,
        )
    }

    @Test
    fun resolverExclusionsContainTransientSystemOwnAndAssistantPackages() {
        assertTrue("android" in ignored)
        assertTrue(SYSTEM_UI_PACKAGE in ignored)
        assertTrue("com.android.permissioncontroller" in ignored)
        assertTrue("com.google.android.permissioncontroller" in ignored)
        assertTrue(ownPackage in ignored)
        assertTrue(VENDOR_GAME_ASSISTANT_PACKAGES.all(ignored::contains))
    }

    @Test
    fun realDifferentAppReplacesContextEvenWhileOwnWindowIsFiltered() {
        val snapshot = snapshotOf(
            VisibleAppWindow(ownPackage, 0, isFocused = true, isActive = true),
            VisibleAppWindow("com.android.launcher", 0, isActive = true),
        )
        val current = CompactProfilePickerForegroundLease(app("com.game"), 30_000L)
        val updated = updateFromSnapshot(current, snapshot, nowUptimeMs = 100L)

        assertEquals("com.android.launcher", updated.foreground?.packageName)
        assertEquals(30_100L, updated.expiresAtUptimeMs)
    }

    @Test
    fun staleCapturedPickerContextCannotMutateThePreviousApp() {
        assertTrue(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.game",
                capturedSessionToken = 7L,
                currentSessionToken = 7L,
                currentLeaseExpiresAtUptimeMs = 30_000L,
                nowUptimeMs = 100L,
            ),
        )
        assertFalse(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.android.launcher",
                capturedSessionToken = 7L,
                currentSessionToken = 7L,
                currentLeaseExpiresAtUptimeMs = 30_000L,
                nowUptimeMs = 100L,
            ),
        )
        assertFalse(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.game",
                capturedSessionToken = 7L,
                currentSessionToken = 8L,
                currentLeaseExpiresAtUptimeMs = 30_000L,
                nowUptimeMs = 100L,
            ),
        )
        assertFalse(
            isCompactProfilePickerMutationContextCurrent(
                capturedPackageName = "com.game",
                currentPackageName = "com.game",
                capturedSessionToken = 7L,
                currentSessionToken = 7L,
                currentLeaseExpiresAtUptimeMs = 30_000L,
                nowUptimeMs = 30_000L,
            ),
        )
    }

    @Test
    fun staleForegroundContextCannotReachHardwareMutation() = runTest {
        var mutationCount = 0

        val staleRan = runCompactProfilePickerMutationIfCurrent(
            capturedForeground = app("com.game"),
            ensureCurrent = { false },
        ) {
            mutationCount++
        }
        val globalRan = runCompactProfilePickerMutationIfCurrent(
            capturedForeground = null,
            ensureCurrent = { false },
        ) {
            mutationCount++
        }

        assertFalse(staleRan)
        assertTrue(globalRan)
        assertEquals(1, mutationCount)
    }
}
