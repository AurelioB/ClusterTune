package com.aure.clustertune.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Serializes event-driven app-profile transitions for every visible display. */
class AppProfileCoordinator(
    context: Context,
    private val repository: PerformanceRepository,
    private val profileStorage: ProfileStorage,
    private val settingsStorage: SettingsStorage,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val launchablePackages = mutableMapOf<String, Boolean>()
    private val homePackages by lazy {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
    }
    private var started = false
    private var appOverrideActive = false
    private var legacyEffectiveStateUnknown = true
    private var observedEffectiveGeneration: Long? = null
    private var lastAppliedSignature: AppTargetSignature? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
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
            combine(
                VisibleAppWindowEvents.snapshots,
                configuration,
            ) { snapshot, config ->
                CoordinatorInput(
                    visibleApps = VisibleApps(
                        packages = snapshot.packages,
                        isInteractive = snapshot.isInteractive,
                    ),
                    configuration = config,
                )
            }
                .distinctUntilChanged()
                .collect { input ->
                    runCatching { reconcile(input) }
                        .onFailure { error -> Log.e(TAG, "App-profile reconciliation failed", error) }
                }
        }
    }

    fun stop() {
        scope.cancel()
        started = false
    }

    private suspend fun reconcile(input: CoordinatorInput) {
        input.configuration.effectiveState?.let { effective ->
            if (effective.generation != observedEffectiveGeneration) {
                observedEffectiveGeneration = effective.generation
                appOverrideActive = effective.source == EffectiveProfileSource.APP ||
                    effective.source == EffectiveProfileSource.COMBINED
                legacyEffectiveStateUnknown = false
            }
        }
        if (!input.visibleApps.isInteractive) return
        if (input.configuration.effectiveState?.source == EffectiveProfileSource.SLEEP) return

        val assignments = input.configuration.assignments.assignments
        val assignmentsByPackage = assignments.associateBy { it.packageName }
        val visiblePackages = input.visibleApps.packages
        val visibleAssignments = visiblePackages
            .mapNotNull(assignmentsByPackage::get)
            .distinctBy { it.packageName }
            .sortedBy { it.packageName }

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

    private suspend fun applyVisibleProfiles(
        assignments: List<AppProfileAssignment>,
        signature: AppTargetSignature,
    ) {
        repository.applyVisibleAppProfilesTemporarily(assignments)
            .onSuccess { applied ->
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
                appOverrideActive = true
                lastAppliedSignature = signature
                QuickSettingsTileRefresher.requestUpdate(appContext)
                showProfileToast(applied.profileName)
            }
            .onFailure { error -> Log.e(TAG, "Unable to apply visible app profiles", error) }
    }

    private suspend fun restoreNormalProfile() {
        repository.restoreNormalProfileTemporarilyWithIdentity()
            .onSuccess { restored ->
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
        if (packageName.isBlank() || packageName in TRANSIENT_PACKAGES) {
            return false
        }
        val inputMethodPackage = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.substringBefore('/')
        if (packageName == inputMethodPackage) return false
        return launchablePackages.getOrPut(packageName) {
            packageManager.getLaunchIntentForPackage(packageName) != null || packageName in homePackages
        }
    }

    private data class CoordinatorInput(
        val visibleApps: VisibleApps,
        val configuration: ProfileConfiguration,
    )

    private data class VisibleApps(
        val packages: Set<String>,
        val isInteractive: Boolean,
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

    private companion object {
        const val TAG = "AppProfileCoordinator"
        val TRANSIENT_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
