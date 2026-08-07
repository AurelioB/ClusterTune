package com.aure.clustertune.data

import com.aure.clustertune.model.CpuPolicyInfo

class CpuPolicyDetector(
    private val fileSystem: SysfsFileSystem = RealSysfsFileSystem(),
    private val privilegedReader: PrivilegedSysfsReader,
    private val privilegedLister: PrivilegedSysfsLister? = null,
    private val policyRoot: String = "/sys/devices/system/cpu/cpufreq",
) {
    fun detectPolicies(): List<CpuPolicyInfo> {
        val unprivilegedDirectories = fileSystem.listPolicyDirectories(policyRoot)
        val directories = unprivilegedDirectories.ifEmpty {
            privilegedLister?.listChildrenWithPrefix(policyRoot, "policy").orEmpty()
        }
        return directories
            .sortedBy(::policyIdOrMax)
            .mapNotNull(::parsePolicy)
            .sortedBy { it.id }
    }

    fun readCurrentMaxValues(policies: List<CpuPolicyInfo>): Map<Int, Int> {
        return policies.mapNotNull { policy ->
            readText(policy.scalingMaxPath)?.toIntOrNull()?.let { policy.id to it }
        }.toMap()
    }

    fun readCurrentMinValues(policies: List<CpuPolicyInfo>): Map<Int, Int> {
        return policies.mapNotNull { policy ->
            readText(policy.scalingMinPath)?.toIntOrNull()?.let { policy.id to it }
        }.toMap()
    }

    private fun parsePolicy(policyPath: String): CpuPolicyInfo? {
        val policyName = policyPath.substringAfterLast('/')
        val id = policyName.removePrefix("policy").toIntOrNull() ?: return null
        val scalingMaxPath = "$policyPath/scaling_max_freq"
        val scalingMinPath = "$policyPath/scaling_min_freq"
        val rawSupported = parseFrequencies(readText("$policyPath/scaling_available_frequencies"))
        val cpuIds = parseCpuIds(readText("$policyPath/affected_cpus"))
            .ifEmpty { parseCpuIds(readText("$policyPath/related_cpus")) }
            .ifEmpty { listOf(id) }
        val cpuInfoMax = readText("$policyPath/cpuinfo_max_freq")?.toIntOrNull()
        val cpuInfoMin = readText("$policyPath/cpuinfo_min_freq")?.toIntOrNull()
        val scalingMax = readText(scalingMaxPath)?.toIntOrNull()
        val timeInStateFrequencies = readTimeInStateFrequencies("$policyPath/stats/time_in_state")
        val positiveTimeInStateFrequencies = readPositiveTimeInStateFrequencies("$policyPath/stats/time_in_state")
        val timeInStateMax = timeInStateFrequencies.maxOrNull()
        // scaling_min_freq is mutable and may have been left artificially high by a
        // previous build or an OEM service. Never use it as the repair floor.
        val minimumCandidates = listOfNotNull(cpuInfoMin?.takeIf { it > 0 }) +
            rawSupported.filter { it > 0 } +
            positiveTimeInStateFrequencies.filter { it > 0 }
        val hardwareMinFreq = minimumCandidates.minOrNull() ?: return null
        val minFreq = readText(scalingMinPath)?.toIntOrNull()?.takeIf { it > 0 } ?: hardwareMinFreq
        val supported = rawSupported.ifEmpty {
            buildFallbackFrequencies(
                minFreq = minFreq,
                maxFreq = maxOfNotNull(cpuInfoMax, scalingMax) ?: 0,
                currentMaxFreq = scalingMax ?: cpuInfoMax ?: 0,
            )
        }
        val selectableMax = supported.lastOrNull() ?: maxOfNotNull(scalingMax, cpuInfoMax) ?: return null
        val observedMax = maxOfNotNull(cpuInfoMax, scalingMax, timeInStateMax, selectableMax) ?: selectableMax
        val currentMax = scalingMax ?: supported.lastOrNull() ?: cpuInfoMax ?: selectableMax

        return CpuPolicyInfo(
            id = id,
            policyPath = policyPath,
            scalingMaxPath = scalingMaxPath,
            currentMaxFreq = currentMax,
            selectableMaxFreq = selectableMax,
            observedMaxFreq = observedMax,
            minFreq = minFreq,
            supportedFrequencies = supported,
            cpuIds = cpuIds,
            scalingMinPath = scalingMinPath,
            hardwareMinFreq = hardwareMinFreq,
            minimumCandidates = minimumCandidates.distinct().sorted(),
        )
    }

    internal fun parseFrequencies(raw: String?): List<Int> {
        return raw.orEmpty()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .sorted()
    }

    internal fun buildFallbackFrequencies(
        minFreq: Int,
        maxFreq: Int,
        currentMaxFreq: Int,
    ): List<Int> {
        return listOf(minFreq, currentMaxFreq, maxFreq)
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    internal fun parseCpuIds(raw: String?): List<Int> {
        return raw.orEmpty()
            .split(Regex("\\s+"))
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .sorted()
    }

    private fun readTimeInStateFrequencies(path: String): List<Int> {
        return readText(path)
            ?.lineSequence()
            ?.mapNotNull { line ->
                line.trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toIntOrNull()
            }
            ?.toList()
            .orEmpty()
    }

    private fun readPositiveTimeInStateFrequencies(path: String): List<Int> {
        return readText(path)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                val frequency = fields.getOrNull(0)?.toIntOrNull()
                val residency = fields.getOrNull(1)?.toLongOrNull()
                frequency?.takeIf { it > 0 && residency != null && residency > 0 }
            }
            ?.toList()
            .orEmpty()
    }

    private fun maxOfNotNull(vararg values: Int?): Int? {
        return values.filterNotNull().maxOrNull()
    }

    private fun readText(path: String): String? {
        // Reading sysfs must not mutate its permissions. Some vendor services
        // temporarily adjust these nodes, and a persistent chmod can leave a
        // policy stuck at a stale minimum after the app exits.
        return fileSystem.readText(path)?.trim()?.takeIf { it.isNotEmpty() }
            ?: privilegedReader.readText(path)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun policyIdOrMax(policyPath: String): Int {
        return policyPath.substringAfterLast('/').removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE
    }
}
