package com.aure.clustertune.apps

import com.aure.clustertune.model.PerformanceProfile

/** A profile target resolved for an application visible on one display. */
data class VisibleAppProfileTarget(
    val packageName: String,
    val appLabel: String = "",
    val profileId: String? = null,
    val profileName: String? = null,
    val cpuMaxFrequencies: Map<Int, Int> = emptyMap(),
    val gpuMaxFrequencyHz: Int? = null,
)

data class CombinedProfilePresentation(
    val id: String?,
    val name: String,
    val isCombined: Boolean,
)

data class CombinedAppProfileResult(
    val contributors: List<VisibleAppProfileTarget>,
    val cpuMaxFrequencies: Map<Int, Int>,
    val gpuMaxFrequencyHz: Int?,
    val presentation: CombinedProfilePresentation?,
)

/** Combines visible application targets without consulting Android or any mutable state. */
object CombinedAppProfileResolver {
    const val COMBINED_PROFILE_ID = "virtual_combined"

    fun resolve(
        targets: Iterable<VisibleAppProfileTarget>,
        knownProfiles: Iterable<PerformanceProfile> = emptyList(),
    ): CombinedAppProfileResult {
        val grouped = targets
            .filter { it.packageName.isNotBlank() }
            .groupBy { it.packageName }
        if (grouped.isEmpty()) {
            return CombinedAppProfileResult(emptyList(), emptyMap(), null, null)
        }

        // A package can be reported by more than one display. Keep one stable identity,
        // while retaining all reports for the component-wise maximum below.
        val contributors = grouped.values.map { samePackage ->
            samePackage.sortedWith(targetComparator).first()
        }.sortedBy { it.packageName }
        val allTargets = grouped.values.flatten()
        val cpu = allTargets
            .flatMap { it.cpuMaxFrequencies.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.maxOrNull()!! }
            .toSortedMap()
        val gpu = allTargets.mapNotNull { it.gpuMaxFrequencyHz }.maxOrNull()

        val known = knownProfiles.firstOrNull {
            it.maxFrequencies == cpu && it.gpuMaxFrequencyHz == gpu
        }
        val combinesMultipleApps = contributors.size > 1
        val presentation = when {
            known != null -> CombinedProfilePresentation(known.id, known.name, combinesMultipleApps)
            contributors.size == 1 -> {
                val only = contributors.single()
                CombinedProfilePresentation(only.profileId, only.profileName ?: only.profileId ?: only.appLabel, false)
            }
            else -> CombinedProfilePresentation(null, "Combined", true)
        }
        return CombinedAppProfileResult(contributors, cpu, gpu, presentation)
    }

    private val targetComparator = compareBy<VisibleAppProfileTarget>(
        { it.profileId ?: "" },
        { it.profileName ?: "" },
        { it.appLabel },
        { it.cpuMaxFrequencies.entries.sortedBy { entry -> entry.key }.toString() },
        { it.gpuMaxFrequencyHz ?: Int.MIN_VALUE },
    )
}
