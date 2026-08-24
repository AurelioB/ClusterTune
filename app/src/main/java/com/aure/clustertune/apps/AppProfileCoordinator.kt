package com.aure.clustertune.apps

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.aure.clustertune.autotune.AdaptiveTuneRuntime
import com.aure.clustertune.autotune.AdaptiveTuneRunResult
import com.aure.clustertune.autotune.AdaptiveTuneSessionRunner
import com.aure.clustertune.autotune.AdaptiveTuneTermination
import com.aure.clustertune.data.PerformanceRepository
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.model.AppProfileAssignment
import com.aure.clustertune.model.EffectiveProfileSource
import com.aure.clustertune.model.EffectiveProfileState
import com.aure.clustertune.model.PerformanceProfile
import com.aure.clustertune.model.ProfileStateResolver
import com.aure.clustertune.tile.QuickSettingsTileRefresher
import com.aure.clustertune.ui.SingleToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes event-driven app-profile transitions for every visible display. */
class AppProfileCoordinator(
    context: Context,
    private val repository: PerformanceRepository,
    private val profileStorage: ProfileStorage,
    private val settingsStorage: SettingsStorage,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reconcileMutex = PROCESS_RECONCILE_MUTEX
    private val reconcileRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val autoTuneRunner = AdaptiveTuneSessionRunner(repository)
    private val excludedPackages = TRANSIENT_PACKAGES +
        VENDOR_GAME_ASSISTANT_PACKAGES + appContext.packageName
    private var started = false
    @Volatile
    private var terminated = false
    @Volatile
    private var coordinatorLease: Long? = null
    private var appOverrideActive = false
    private var legacyEffectiveStateUnknown = true
    private var observedEffectiveGeneration: Long? = null
    private var lastAppliedSignature: AppTargetSignature? = null
    @Volatile
    private var autoTuneJob: Job? = null
    @Volatile
    private var reconcileJob: Job? = null
    @Volatile
    private var autoTuneCleanupRetryJob: Job? = null
    private var activeAutoTune: ActiveAutoTune? = null
    private var autoTuneCleanupPending = false
    private var autoTuneCleanupSignature: AutoTuneSignature? = null
    private var pausedAutoTuneSignature: AutoTuneSignature? = null
    private var sleepPauseActive = false
    private var nextAutoTuneRunId = 0L

    fun start() {
        if (started || terminated) return
        coordinatorLease = AdaptiveTuneRuntime.claimCoordinatorLease()
        started = true
        reconcileJob = scope.launch {
            val assignmentConfiguration = combine(
                profileStorage.appProfileAssignments,
                profileStorage.profiles,
            ) { assignments, storedProfiles ->
                AssignmentConfiguration(
                    assignments = assignments.sortedBy { it.packageName },
                    storedProfiles = storedProfiles,
                )
            }
            val configuration = combine(
                assignmentConfiguration,
                profileStorage.effectiveProfileState,
            ) { assignments, effective ->
                ProfileConfiguration(assignments, effective)
            }
            val inputs = combine(
                VisibleAppWindowEvents.snapshots,
                configuration,
            ) { snapshot, config ->
                CoordinatorInput(
                    visibleApps = snapshot,
                    configuration = config,
                )
            }
                .distinctUntilChanged()
            combine(
                inputs,
                reconcileRequests.onStart { emit(Unit) },
            ) { input, _ -> input }
                .collect { input ->
                    try {
                        reconcileMutex.withLock { reconcile(input) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.e(TAG, "App-profile reconciliation failed", error)
                    }
                }
        }
    }

    fun stop() {
        if (terminated) return
        terminated = true
        started = false
        coordinatorLease?.let { lease ->
            AdaptiveTuneRuntime.stopCoordinatorIfCurrent(
                lease = lease,
                message = "App profile automation stopped",
            )
        }
        reconcileJob?.cancel()
        autoTuneJob?.cancel()
        autoTuneCleanupRetryJob?.cancel()
        scope.launch {
            val cleanupSucceeded = reconcileMutex.withLock {
                val stoppedSignature = activeAutoTune?.signature ?: autoTuneCleanupSignature
                pausedAutoTuneSignature = null
                val cleaned = stopAutoTune("App profile automation stopped")
                val lease = coordinatorLease
                if (cleaned && stoppedSignature != null && lease != null) {
                    completeAutoTuneCleanup(
                        signature = stoppedSignature,
                        cleanupReason = "App profile automation stopped",
                        lease = lease,
                        releasePauseAfterCleanup = false,
                    )
                }
                cleaned
            }
            if (!cleanupSucceeded) {
                coordinatorLease?.let { lease -> retryCleanupAfterCoordinatorStop(lease) }
            }
            scope.cancel()
        }
    }

    private suspend fun reconcile(input: CoordinatorInput) {
        if (terminated || !isCoordinatorCurrent()) return
        var effectiveStateChanged = false
        input.configuration.effectiveState?.let { effective ->
            if (effective.generation != observedEffectiveGeneration) {
                effectiveStateChanged = true
                observedEffectiveGeneration = effective.generation
                appOverrideActive = effective.source == EffectiveProfileSource.APP ||
                    effective.source == EffectiveProfileSource.COMBINED
                legacyEffectiveStateUnknown = false
            }
        }
        if (!input.visibleApps.isInteractive) {
            pausedAutoTuneSignature = null
            sleepPauseActive = false
            stopAutoTune("Display is no longer interactive")
            return
        }

        val assignments = input.configuration.assignments.assignments
        val assignmentsByPackage = assignments.associateBy { it.packageName }
        val visiblePackages = input.visibleApps.packages
        val plan = resolveAppAutomationPlan(
            snapshot = input.visibleApps,
            assignments = assignments,
            excludedPackages = excludedPackages,
        )

        val desiredAutoTuneSignature = plan.autoTuneAssignment?.toAutoTuneSignature(
            effectiveTargetFps = requireNotNull(plan.effectiveAutoTuneTargetFps),
            displayId = requireNotNull(plan.foregroundDisplayId),
            displayRefreshRateFps = plan.foregroundDisplayRefreshRateFps,
        )
        if (input.configuration.effectiveState?.source == EffectiveProfileSource.SLEEP) {
            pausedAutoTuneSignature = activeAutoTune?.signature ?: desiredAutoTuneSignature
            sleepPauseActive = true
            stopAutoTune("Sleep profile superseded Auto Tune")
            return
        }
        if (sleepPauseActive) {
            sleepPauseActive = false
            if (pausedAutoTuneSignature == desiredAutoTuneSignature) {
                pausedAutoTuneSignature = null
            }
        }
        if (pausedAutoTuneSignature != null && pausedAutoTuneSignature != desiredAutoTuneSignature) {
            pausedAutoTuneSignature = null
        }
        val effectiveSource = input.configuration.effectiveState?.source
        val fixedProfileSupersededActiveAutoTune = effectiveStateChanged &&
            effectiveSource != null &&
            effectiveSource !in setOf(
                EffectiveProfileSource.APP,
                EffectiveProfileSource.COMBINED,
                EffectiveProfileSource.SLEEP,
            ) &&
            activeAutoTune?.signature == desiredAutoTuneSignature
        if (fixedProfileSupersededActiveAutoTune) {
            pausedAutoTuneSignature = desiredAutoTuneSignature
            stopAutoTune("A fixed profile superseded Auto Tune")
        }
        if (plan.autoTuneAssignment != null) {
            if (pausedAutoTuneSignature != desiredAutoTuneSignature) {
                startAutoTuneIfNeeded(requireNotNull(desiredAutoTuneSignature))
            }
            legacyEffectiveStateUnknown = false
            return
        }

        pausedAutoTuneSignature = null
        if (!stopAutoTune(
                reason = "No automatic app owns the device",
                releasePauseAfterCleanup = true,
            )
        ) {
            return
        }
        if (terminated || !isCoordinatorCurrent()) return
        val visibleAssignments = plan.staticAssignments

        if (visibleAssignments.isNotEmpty()) {
            val relevantProfileIds = visibleAssignments.mapNotNullTo(mutableSetOf()) { it.profileId }
            val signature = AppTargetSignature(
                assignments = visibleAssignments,
                referencedProfiles = input.configuration.assignments.storedProfiles
                    .filter { it.id in relevantProfileIds },
            )
            if (!appOverrideActive || lastAppliedSignature != signature) {
                applyVisibleProfiles(visibleAssignments, signature)
            }
            legacyEffectiveStateUnknown = false
            return
        }

        if (assignments.isEmpty()) {
            if (appOverrideActive) restoreNormalProfile()
            legacyEffectiveStateUnknown = false
            return
        }

        val positiveUnassignedApp = visiblePackages.any { packageName ->
            packageName !in assignmentsByPackage && isUserFacingPackage(packageName)
        }
        // An interactive, confirmed empty snapshot means the previously visible
        // assigned app has gone away. Restore the normal profile once no assigned
        // windows remain; combined profiles are retained above while any assigned
        // window is still visible.
        if ((positiveUnassignedApp || visiblePackages.isEmpty()) &&
            (appOverrideActive || legacyEffectiveStateUnknown)
        ) {
            restoreNormalProfile()
            legacyEffectiveStateUnknown = false
        }
    }

    private suspend fun startAutoTuneIfNeeded(signature: AutoTuneSignature) {
        if (terminated || !isCoordinatorCurrent()) return
        if (activeAutoTune?.signature == signature) return

        if (!stopAutoTune(
                reason = "A different app now owns Auto Tune",
                releasePauseAfterCleanup = true,
            )
        ) {
            pausedAutoTuneSignature = signature
            return
        }
        if (terminated || !isCoordinatorCurrent()) return
        lastAppliedSignature = null
        val run = ActiveAutoTune(
            id = ++nextAutoTuneRunId,
            signature = signature,
        )
        activeAutoTune = run
        autoTuneJob = scope.launch {
            val result = runAutoTuneWithStartupRetries(run)
            handleAutoTuneCompletion(run, result)
        }
    }

    private suspend fun stopAutoTune(
        reason: String,
        releasePauseAfterCleanup: Boolean = false,
    ): Boolean {
        val job = autoTuneJob
        val cleanupRetryJob = autoTuneCleanupRetryJob
        val cleanupSignature = activeAutoTune?.signature ?: autoTuneCleanupSignature
        val cleanupRequired = job != null || activeAutoTune != null || autoTuneCleanupPending
        if (cleanupRequired) {
            autoTuneCleanupPending = true
            if (autoTuneCleanupSignature == null) {
                autoTuneCleanupSignature = cleanupSignature
            }
        }
        autoTuneJob = null
        autoTuneCleanupRetryJob = null
        activeAutoTune = null
        withContext(NonCancellable) {
            job?.cancelAndJoin()
            cleanupRetryJob?.cancelAndJoin()
        }
        val lease = coordinatorLease
        if (lease == null || !AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) {
            return true
        }
        if (!cleanupRequired) {
            AdaptiveTuneRuntime.clearInactiveIfCoordinatorCurrent(lease)
            return true
        }
        val cleanup = repository.ensureAutoTuneStopped(reason, lease)
        if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return true
        return cleanup.fold(
            onSuccess = {
                autoTuneCleanupPending = false
                autoTuneCleanupSignature = null
                AdaptiveTuneRuntime.clearInactiveIfCoordinatorCurrent(lease)
                true
            },
            onFailure = { error ->
                Log.e(TAG, "Unable to stop Auto Tune cleanly", error)
                autoTuneCleanupSignature?.let { signature ->
                    if (!terminated) {
                        scheduleAutoTuneCleanupRetry(
                            signature = signature,
                            cleanupReason = reason,
                            lease = lease,
                            releasePauseAfterCleanup = releasePauseAfterCleanup,
                        )
                    }
                }
                false
            },
        )
    }

    private suspend fun runAutoTuneWithStartupRetries(run: ActiveAutoTune): AdaptiveTuneRunResult {
        var result: AdaptiveTuneRunResult
        var attempt = 0
        var retryAfterGeneration: Long? = null
        do {
            if (terminated || !isCoordinatorCurrent()) {
                throw CancellationException("Auto Tune coordinator was replaced")
            }
            result = autoTuneRunner.run(
                packageName = run.signature.packageName,
                appLabel = run.signature.appLabel,
                targetFps = run.signature.targetFps,
                retryAfterGeneration = retryAfterGeneration,
                coordinatorLease = coordinatorLease,
                onSessionStarted = { markAutoTuneStarted(run) },
            )
            if (result.sessionStarted || !result.isRetryableFrameStartupFailure()) return result
            retryAfterGeneration = result.generation
            attempt += 1
            if (attempt < AUTO_TUNE_START_ATTEMPTS) {
                delay(AUTO_TUNE_START_RETRY_DELAY_MS * attempt)
            }
        } while (attempt < AUTO_TUNE_START_ATTEMPTS)
        return result
    }

    private suspend fun markAutoTuneStarted(run: ActiveAutoTune) {
        reconcileMutex.withLock {
            val active = activeAutoTune
            if (terminated || !isCoordinatorCurrent() || active?.id != run.id) {
                throw CancellationException("Auto Tune owner changed during startup")
            }
            if (active.confirmed) return
            active.confirmed = true
            val signature = active.signature
            val profileName = "Auto Tune · ${signature.targetFps} FPS"
            appOverrideActive = true
            repository.logProfileSwitch(
                profileId = "auto:${signature.targetFps}",
                profileName = profileName,
                trigger = "App visible: ${signature.appLabel} (${signature.packageName})",
                effectiveSource = EffectiveProfileSource.APP,
                contributingPackageNames = listOf(signature.packageName),
            )
            if (terminated || !isCoordinatorCurrent()) return
            QuickSettingsTileRefresher.requestUpdate(appContext)
            showProfileToast(profileName)
        }
    }

    private suspend fun handleAutoTuneCompletion(
        run: ActiveAutoTune,
        result: AdaptiveTuneRunResult,
    ) {
        reconcileMutex.withLock {
            val lease = coordinatorLease
            if (terminated || lease == null || !AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return
            val active = activeAutoTune
            if (active?.id != run.id) return
            autoTuneJob = null
            activeAutoTune = null
            pausedAutoTuneSignature = run.signature
            autoTuneCleanupPending = true
            autoTuneCleanupSignature = run.signature

            val cleanupReason = when (result.termination) {
                AdaptiveTuneTermination.SUPERSEDED -> "Auto Tune was superseded"
                AdaptiveTuneTermination.STOPPED -> "Auto Tune stopped"
                AdaptiveTuneTermination.FAILED -> "Auto Tune failed"
            }
            val cleanup = repository.ensureAutoTuneStopped(cleanupReason, lease)
            if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return
            if (cleanup.isFailure) {
                val error = cleanup.exceptionOrNull()
                    ?: IllegalStateException("Unknown host cleanup error")
                Log.e(TAG, "Unable to verify Auto Tune cleanup", error)
                scheduleAutoTuneCleanupRetry(
                    signature = run.signature,
                    cleanupReason = cleanupReason,
                    lease = lease,
                    releasePauseAfterCleanup = false,
                )
                return
            }
            completeAutoTuneCleanup(
                signature = run.signature,
                cleanupReason = cleanupReason,
                lease = lease,
                releasePauseAfterCleanup = false,
            )
        }
    }

    private fun scheduleAutoTuneCleanupRetry(
        signature: AutoTuneSignature,
        cleanupReason: String,
        lease: Long,
        releasePauseAfterCleanup: Boolean,
    ) {
        autoTuneCleanupRetryJob?.cancel()
        autoTuneCleanupRetryJob = scope.launch {
            var retryAttempt = 0
            val outcome = retryAutoTuneCleanupWithBackoff(
                maxAttempts = AUTO_TUNE_CLEANUP_RETRY_ATTEMPTS,
                initialDelayMillis = AUTO_TUNE_CLEANUP_RETRY_DELAY_MS,
            ) {
                retryAttempt += 1
                reconcileMutex.withLock {
                    if (terminated ||
                        !AdaptiveTuneRuntime.isCoordinatorCurrent(lease) ||
                        autoTuneCleanupSignature != signature ||
                        activeAutoTune != null ||
                        !autoTuneCleanupPending
                    ) {
                        return@withLock AutoTuneCleanupRetryDecision.ABANDON
                    }
                    val retry = repository.ensureAutoTuneStopped(
                        reason = "$cleanupReason (retry $retryAttempt)",
                        coordinatorLease = lease,
                    )
                    if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) {
                        return@withLock AutoTuneCleanupRetryDecision.ABANDON
                    }
                    if (retry.isFailure) {
                        Log.e(TAG, "Unable to verify Auto Tune cleanup on retry $retryAttempt", retry.exceptionOrNull())
                        AutoTuneCleanupRetryDecision.RETRY
                    } else {
                        completeAutoTuneCleanup(
                            signature = signature,
                            cleanupReason = cleanupReason,
                            lease = lease,
                            releasePauseAfterCleanup = releasePauseAfterCleanup,
                        )
                        AutoTuneCleanupRetryDecision.SUCCESS
                    }
                }
            }
            if (outcome == AutoTuneCleanupRetryOutcome.EXHAUSTED) {
                Log.e(TAG, "Auto Tune cleanup retries exhausted for ${signature.packageName}")
            }
        }
    }

    private suspend fun retryCleanupAfterCoordinatorStop(lease: Long) {
        if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return
        val signature = autoTuneCleanupSignature ?: return
        var retryAttempt = 0
        val outcome = retryAutoTuneCleanupWithBackoff(
            maxAttempts = AUTO_TUNE_CLEANUP_RETRY_ATTEMPTS,
            initialDelayMillis = AUTO_TUNE_CLEANUP_RETRY_DELAY_MS,
        ) {
            retryAttempt += 1
            reconcileMutex.withLock {
                if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease) ||
                    autoTuneCleanupSignature != signature ||
                    !autoTuneCleanupPending
                ) {
                    return@withLock AutoTuneCleanupRetryDecision.ABANDON
                }
                val retry = repository.ensureAutoTuneStopped(
                    reason = "App profile automation stopped (retry $retryAttempt)",
                    coordinatorLease = lease,
                )
                if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) {
                    return@withLock AutoTuneCleanupRetryDecision.ABANDON
                }
                if (retry.isFailure) {
                    AutoTuneCleanupRetryDecision.RETRY
                } else {
                    completeAutoTuneCleanup(
                        signature = signature,
                        cleanupReason = "App profile automation stopped",
                        lease = lease,
                        releasePauseAfterCleanup = false,
                    )
                    AutoTuneCleanupRetryDecision.SUCCESS
                }
            }
        }
        if (outcome == AutoTuneCleanupRetryOutcome.EXHAUSTED) {
            Log.e(TAG, "Auto Tune cleanup retries exhausted while stopping the coordinator")
        }
    }

    private suspend fun completeAutoTuneCleanup(
        signature: AutoTuneSignature,
        cleanupReason: String,
        lease: Long,
        releasePauseAfterCleanup: Boolean,
    ) {
        if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return
        autoTuneCleanupPending = false
        autoTuneCleanupSignature = null
        if (releasePauseAfterCleanup) {
            pausedAutoTuneSignature = null
        }
        repository.restoreAutoTuneEffectiveIdentity(
            trigger = "$cleanupReason for ${signature.appLabel}",
            coordinatorLease = lease,
        ).onSuccess { identity ->
            if (!AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) return@onSuccess
            if (identity != null) {
                appOverrideActive = false
                lastAppliedSignature = null
                QuickSettingsTileRefresher.requestUpdate(appContext)
                if (!terminated) {
                    showProfileToast(identity.profileName)
                }
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to restore the effective profile identity", error)
        }
        if (releasePauseAfterCleanup && !terminated && AdaptiveTuneRuntime.isCoordinatorCurrent(lease)) {
            reconcileRequests.tryEmit(Unit)
        }
    }

    private fun isCoordinatorCurrent(): Boolean {
        val lease = coordinatorLease ?: return false
        return AdaptiveTuneRuntime.isCoordinatorCurrent(lease)
    }

    private fun AdaptiveTuneRunResult.isRetryableFrameStartupFailure(): Boolean {
        if (termination != AdaptiveTuneTermination.FAILED || sessionStarted) return false
        val detail = message.orEmpty()
        return detail.contains("surface", ignoreCase = true) ||
            detail.contains("frame", ignoreCase = true) ||
            detail.contains("layer", ignoreCase = true)
    }

    private suspend fun applyVisibleProfiles(
        assignments: List<AppProfileAssignment>,
        signature: AppTargetSignature,
    ) {
        if (terminated || !isCoordinatorCurrent()) return
        repository.applyVisibleAppProfilesTemporarily(assignments)
            .onSuccess { applied ->
                if (terminated || !isCoordinatorCurrent()) return@onSuccess
                val source = if (applied.isCombined) EffectiveProfileSource.COMBINED else EffectiveProfileSource.APP
                repository.logProfileSwitch(
                    profileId = applied.profileId,
                    profileName = applied.profileName,
                    trigger = if (applied.isCombined) {
                        "Visible apps: ${assignments.joinToString { it.appLabel }}"
                    } else {
                        "App visible: ${assignments.single().appLabel} (${assignments.single().packageName})"
                    },
                    effectiveSource = source,
                    contributingPackageNames = applied.contributingPackages,
                )
                if (terminated || !isCoordinatorCurrent()) return@onSuccess
                appOverrideActive = true
                lastAppliedSignature = signature
                QuickSettingsTileRefresher.requestUpdate(appContext)
                showProfileToast(applied.profileName)
            }
            .onFailure { error -> Log.e(TAG, "Unable to apply visible app profiles", error) }
    }

    private suspend fun restoreNormalProfile() {
        if (terminated || !isCoordinatorCurrent()) return
        repository.restoreNormalProfileTemporarilyWithIdentity()
            .onSuccess { restored ->
                if (terminated || !isCoordinatorCurrent()) return@onSuccess
                val source = when (restored.profileId) {
                    ProfileStateResolver.STOCK_PROFILE_ID -> EffectiveProfileSource.STOCK
                    ProfileStateResolver.MANUAL_PROFILE_ID -> EffectiveProfileSource.MANUAL
                    else -> EffectiveProfileSource.NORMAL
                }
                repository.logProfileSwitch(
                    profileId = restored.profileId,
                    profileName = restored.profileName,
                    trigger = "No assigned app visible",
                    effectiveSource = source,
                )
                if (terminated || !isCoordinatorCurrent()) return@onSuccess
                appOverrideActive = false
                lastAppliedSignature = null
                QuickSettingsTileRefresher.requestUpdate(appContext)
                showProfileToast(restored.profileName)
            }
            .onFailure { error -> Log.e(TAG, "Unable to restore the normal profile", error) }
    }

    private suspend fun showProfileToast(profileName: String) {
        if (!settingsStorage.settings.first().profileSwitchToastsEnabled) return
        SingleToast.show(appContext, profileName, Toast.LENGTH_SHORT)
    }

    private fun isUserFacingPackage(packageName: String): Boolean {
        if (packageName.isBlank() || packageName in excludedPackages) {
            return false
        }
        val inputMethodPackage = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
        if (packageName == inputMethodPackage) return false
        return true
    }

    private data class CoordinatorInput(
        val visibleApps: VisibleAppSnapshot,
        val configuration: ProfileConfiguration,
    )

    private data class AssignmentConfiguration(
        val assignments: List<AppProfileAssignment>,
        // Profile edits must reconcile a currently visible assignment even when its id is unchanged.
        val storedProfiles: List<PerformanceProfile>,
    )

    private data class ProfileConfiguration(
        val assignments: AssignmentConfiguration,
        val effectiveState: EffectiveProfileState?,
    )

    private data class AppTargetSignature(
        val assignments: List<AppProfileAssignment>,
        val referencedProfiles: List<PerformanceProfile>,
    )

    private data class AutoTuneSignature(
        val packageName: String,
        val appLabel: String,
        val configuredTargetFps: Int,
        val targetFps: Int,
        val displayId: Int,
        val displayRefreshRateFps: Int?,
    )

    private data class ActiveAutoTune(
        val id: Long,
        val signature: AutoTuneSignature,
        var confirmed: Boolean = false,
    )

    private fun AppProfileAssignment.toAutoTuneSignature(
        effectiveTargetFps: Int,
        displayId: Int,
        displayRefreshRateFps: Int?,
    ) = AutoTuneSignature(
        packageName = packageName,
        appLabel = appLabel,
        configuredTargetFps = requireNotNull(autoTuneTargetFps),
        targetFps = effectiveTargetFps,
        displayId = displayId,
        displayRefreshRateFps = displayRefreshRateFps,
    )

    private companion object {
        const val TAG = "AppProfileCoordinator"
        const val AUTO_TUNE_START_ATTEMPTS = 3
        const val AUTO_TUNE_START_RETRY_DELAY_MS = 500L
        const val AUTO_TUNE_CLEANUP_RETRY_ATTEMPTS = 3
        const val AUTO_TUNE_CLEANUP_RETRY_DELAY_MS = 1_000L
        val PROCESS_RECONCILE_MUTEX = Mutex()
        val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}

internal enum class AutoTuneCleanupRetryDecision {
    SUCCESS,
    RETRY,
    ABANDON,
}

internal enum class AutoTuneCleanupRetryOutcome {
    SUCCEEDED,
    EXHAUSTED,
    ABANDONED,
}

internal suspend fun retryAutoTuneCleanupWithBackoff(
    maxAttempts: Int,
    initialDelayMillis: Long,
    delayBeforeAttempt: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> AutoTuneCleanupRetryDecision,
): AutoTuneCleanupRetryOutcome {
    require(maxAttempts > 0) { "Auto Tune cleanup retries must be positive" }
    require(initialDelayMillis > 0L) { "Auto Tune cleanup retry delay must be positive" }
    var nextDelay = initialDelayMillis
    repeat(maxAttempts) {
        delayBeforeAttempt(nextDelay)
        when (attempt()) {
            AutoTuneCleanupRetryDecision.SUCCESS -> return AutoTuneCleanupRetryOutcome.SUCCEEDED
            AutoTuneCleanupRetryDecision.ABANDON -> return AutoTuneCleanupRetryOutcome.ABANDONED
            AutoTuneCleanupRetryDecision.RETRY -> Unit
        }
        nextDelay = (nextDelay * 2L).coerceAtMost(MAX_AUTO_TUNE_CLEANUP_RETRY_DELAY_MS)
    }
    return AutoTuneCleanupRetryOutcome.EXHAUSTED
}

private const val MAX_AUTO_TUNE_CLEANUP_RETRY_DELAY_MS = 30_000L
