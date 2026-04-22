package com.aure.androidtuner.data

import com.aure.androidtuner.model.CpuPolicyInfo

class CpuPolicyDetector(
    private val fileSystem: SysfsFileSystem = RealSysfsFileSystem(),
    private val policyRoot: String = "/sys/devices/system/cpu/cpufreq",
) {

    fun detectPolicies(): List<CpuPolicyInfo> {
        return fileSystem.listPolicyDirectories(policyRoot)
            .mapNotNull(::parsePolicy)
            .sortedBy { it.id }
    }

    private fun parsePolicy(policyPath: String): CpuPolicyInfo? {
        val policyName = policyPath.substringAfterLast('/')
        val id = policyName.removePrefix("policy").toIntOrNull() ?: return null
        val scalingMaxPath = "$policyPath/scaling_max_freq"
        val currentMax = fileSystem.readText(scalingMaxPath)?.toIntOrNull() ?: return null
        val stockMax = fileSystem.readText("$policyPath/cpuinfo_max_freq")?.toIntOrNull() ?: currentMax
        val minFreq = fileSystem.readText("$policyPath/cpuinfo_min_freq")?.toIntOrNull()
            ?: fileSystem.readText("$policyPath/scaling_min_freq")?.toIntOrNull()
            ?: 0
        val supported = parseFrequencies(fileSystem.readText("$policyPath/scaling_available_frequencies"))
            .ifEmpty {
                buildFallbackFrequencies(
                    minFreq = minFreq,
                    maxFreq = stockMax,
                    currentMaxFreq = currentMax,
                )
            }

        return CpuPolicyInfo(
            id = id,
            policyPath = policyPath,
            scalingMaxPath = scalingMaxPath,
            currentMaxFreq = currentMax,
            stockMaxFreq = stockMax,
            minFreq = minFreq,
            supportedFrequencies = supported,
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
}
