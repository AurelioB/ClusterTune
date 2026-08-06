package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment

/** Pure, deterministic transition state for app-profile automation. */
internal class AppProfileTransitionState(
    private val restoreGraceMs: Long = DEFAULT_RESTORE_GRACE_MS,
) {
    private var activeAssignment: AppProfileAssignment? = null
    private var unassignedSince: Long? = null
    private var startupPending = true

    fun activeAssignment(): AppProfileAssignment? = activeAssignment

    fun observe(
        nowMs: Long,
        foregroundPackage: String?,
        assignments: List<AppProfileAssignment>,
        transientForeground: Boolean = foregroundPackage == null,
    ): Action {
        if (assignments.isEmpty()) {
            unassignedSince = null
            if (startupPending) return Action.Restore
            return if (activeAssignment != null) Action.Restore else Action.None
        }
        val assignment = assignments.firstOrNull { it.packageName == foregroundPackage }
        if (assignment != null) {
            unassignedSince = null
            return if (startupPending || activeAssignment != assignment) Action.Apply(assignment) else Action.None
        }
        if (transientForeground) {
            unassignedSince = null
            // Usage events briefly report the launcher, System UI, or no package
            // while an app is still resuming. Never drop a valid assignment on
            // this ambiguous signal; restore only after a positively identified
            // unassigned, launchable package has remained focused.
            return Action.None
        }
        if (startupPending) return Action.Restore
        if (activeAssignment == null) return Action.None
        val since = unassignedSince ?: nowMs.also { unassignedSince = it }
        return if (nowMs - since >= restoreGraceMs) Action.Restore else Action.None
    }

    fun onApplied(assignment: AppProfileAssignment) {
        activeAssignment = assignment
        startupPending = false
        unassignedSince = null
    }

    fun onRestored() {
        activeAssignment = null
        startupPending = false
        unassignedSince = null
    }

    sealed interface Action {
        data object None : Action
        data class Apply(val assignment: AppProfileAssignment) : Action
        data object Restore : Action
    }

    companion object {
        const val DEFAULT_RESTORE_GRACE_MS = 1_500L
    }
}
