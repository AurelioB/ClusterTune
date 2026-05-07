package com.aure.clustertune.data

import android.util.Log
import com.aure.clustertune.model.CpuPolicyInfo

class CpuPolicyDetector(
    private val fileSystem: SysfsFileSystem = RealSysfsFileSystem(),
    private val privilegedReader: PrivilegedSysfsReader,
    private val privilegedLister: PrivilegedSysfsLister? = null,
    private val policyRoot: String = "/sys/devices/system/cpu/cpufreq",
) {
    fun detectPolicies(): List<CpuPolicyInfo> {
        val directoriesFromFs = fileSystem.listPolicyDirectories(policyRoot)
        Log.d(LOG_TAG, "Unprivileged listing of $policyRoot -> ${directoriesFromFs.size} entries: $directoriesFromFs")
        // On the Odin 2 Mini, /sys/devices/system/cpu/cpufreq is not
        // listable from the untrusted_app SELinux domain, even though the
        // individual policyN/* files are readable through PServer. When the
        // unprivileged listing yields nothing, try the privileged lister so
        // we can still discover the policies. The previous "do not fall
        // back to normal reads when privileged reader is configured" test
        // relied on the unprivileged listing producing the directory names;
        // we preserve that behaviour by only consulting the privileged
        // lister when the unprivileged listing is empty.
        val directories = if (directoriesFromFs.isNotEmpty()) {
            directoriesFromFs
        } else {
            val fallback = privilegedLister?.listChildrenWithPrefix(policyRoot, "policy")
            Log.d(LOG_TAG, "Privileged-listing fallback: lister=${privilegedLister != null}, result=$fallback")
            fallback.orEmpty()
        }
        val policies = directories
            .sortedBy(::policyIdOrMax)
            .mapNotNull(::parsePolicy)
            .sortedBy { it.id }
        Log.d(LOG_TAG, "detectPolicies finished: ${policies.size} policies parsed (ids=${policies.map { it.id }})")
        return policies
    }

    fun readCurrentMaxValues(policies: List<CpuPolicyInfo>): Map<Int, Int> {
        return policies.mapNotNull { policy ->
            readText(policy.scalingMaxPath)?.toIntOrNull()?.let { policy.id to it }
        }.toMap()
    }

    private fun parsePolicy(policyPath: String): CpuPolicyInfo? {
        val policyName = policyPath.substringAfterLast('/')
        val id = policyName.removePrefix("policy").toIntOrNull() ?: return null
        val scalingMaxPath = "$policyPath/scaling_max_freq"
        val rawSupported = parseFrequencies(readText("$policyPath/scaling_available_frequencies"))
        val cpuIds = parseCpuIds(readText("$policyPath/affected_cpus"))
            .ifEmpty { parseCpuIds(readText("$policyPath/related_cpus")) }
            .ifEmpty { listOf(id) }
        val cpuInfoMax = readText("$policyPath/cpuinfo_max_freq")?.toIntOrNull()
        val scalingMax = readText(scalingMaxPath)?.toIntOrNull()
        val timeInStateMax = readTimeInStateMax("$policyPath/stats/time_in_state")
        val minFreq = readText("$policyPath/scaling_min_freq")?.toIntOrNull()
            ?: rawSupported.firstOrNull()
            ?: 0
        Log.d(
            LOG_TAG,
            "policy$id reads: cpuIds=$cpuIds, scalingMax=$scalingMax, cpuinfoMax=$cpuInfoMax, " +
                "minFreq=$minFreq, available=${rawSupported.size} steps, timeInStateMax=$timeInStateMax",
        )
        val supported = rawSupported.ifEmpty {
            buildFallbackFrequencies(
                minFreq = minFreq,
                maxFreq = maxOfNotNull(cpuInfoMax, scalingMax) ?: 0,
                currentMaxFreq = scalingMax ?: cpuInfoMax ?: 0,
            )
        }
        val selectableMax = supported.lastOrNull() ?: maxOfNotNull(scalingMax, cpuInfoMax) ?: run {
            Log.d(LOG_TAG, "policy$id rejected: no usable max frequency from any source")
            return null
        }
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

    private fun readTimeInStateMax(path: String): Int? {
        return readText(path)
            ?.lineSequence()
            ?.mapNotNull { line ->
                line.trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toIntOrNull()
            }
            ?.maxOrNull()
    }

    private fun maxOfNotNull(vararg values: Int?): Int? {
        return values.filterNotNull().maxOrNull()
    }

    private fun readText(path: String): String? {
        return privilegedReader
            .readText(path)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun policyIdOrMax(policyPath: String): Int {
        return policyPath.substringAfterLast('/').removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE
    }

    private companion object {
        const val LOG_TAG = "ClusterTune"
    }
}
