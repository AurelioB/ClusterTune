package com.aure.clustertune.apps

import com.aure.clustertune.model.AppProfileAssignment

/**
 * Resolves the single frame-aware owner of the device-wide adaptive envelope.
 *
 * CPU and GPU frequency ceilings are global, so an automatic assignment must
 * never be inferred by combining unrelated FPS targets from several displays.
 * The focused/active window owns automatic tuning; ordinary assignments remain
 * available to the existing least-restrictive static resolver.
 */
internal data class AppAutomationPlan(
    val foregroundPackageName: String?,
    val autoTuneAssignment: AppProfileAssignment?,
    val staticAssignments: List<AppProfileAssignment>,
)

internal fun resolveAppAutomationPlan(
    snapshot: VisibleAppSnapshot,
    assignments: List<AppProfileAssignment>,
    excludedPackages: Set<String> = emptySet(),
): AppAutomationPlan {
    if (!snapshot.isInteractive) {
        return AppAutomationPlan(null, null, emptyList())
    }

    val assignmentsByPackage = assignments
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.hasValidTarget }
        .distinctBy { it.packageName }
        .associateBy { it.packageName }
    val visiblePackageNames = snapshot.packages.filterNotTo(linkedSetOf()) { it in excludedPackages }
    val foregroundPackageName = selectVisibleAppWindow(
        snapshot = snapshot,
        excludedPackages = excludedPackages,
    )?.packageName
    val autoTuneAssignment = foregroundPackageName
        ?.let(assignmentsByPackage::get)
        ?.takeIf(AppProfileAssignment::isAutoTune)
    val staticAssignments = visiblePackageNames
        .mapNotNull(assignmentsByPackage::get)
        .filterNot(AppProfileAssignment::isAutoTune)
        .distinctBy { it.packageName }
        .sortedBy { it.packageName }

    return AppAutomationPlan(
        foregroundPackageName = foregroundPackageName,
        autoTuneAssignment = autoTuneAssignment,
        staticAssignments = staticAssignments,
    )
}
