package com.aure.clustertune.data

import android.content.Context
import android.os.Build
import com.aure.clustertune.model.GpuPolicyInfo

interface GpuCeilingStore {
    fun read(path: String): Int?
    fun writeIfHigher(path: String, value: Int)
}

class SharedPreferencesGpuCeilingStore(context: Context) : GpuCeilingStore {
    private val preferences = context.applicationContext.getSharedPreferences("gpu_observed_ceilings", Context.MODE_PRIVATE)
    private val deviceIdentity = Build.FINGERPRINT.ifBlank { "unknown" }
    override fun read(path: String): Int? = preferences.getInt(gpuCeilingPreferenceKey(deviceIdentity, path), 0).takeIf { it > 0 }
    override fun writeIfHigher(path: String, value: Int) {
        val key = gpuCeilingPreferenceKey(deviceIdentity, path)
        if (value > preferences.getInt(key, 0)) preferences.edit().putInt(key, value).commit()
    }
}

internal fun gpuCeilingPreferenceKey(deviceIdentity: String, path: String): String =
    "${deviceIdentity.ifBlank { "unknown" }}|$path"

/** Discovers the common KGSL and devfreq GPU frequency interfaces without changing sysfs. */
class GpuPolicyDetector(
    private val fileSystem: SysfsFileSystem = RealSysfsFileSystem(),
    private val privilegedReader: PrivilegedSysfsReader = PrivilegedSysfsReader { null },
    private val privilegedLister: PrivilegedSysfsLister? = null,
    private val ceilingStore: GpuCeilingStore = InMemoryGpuCeilingStore,
) {
    // Some kernels omit available-frequency enumeration. Keep the first
    // known ceiling for each domain so a later cap does not redefine Stock
    // during a refresh. The cache belongs to this detector instance, which is
    // scoped with the repository and avoids leaking ceilings between devices.
    private val observedCeilings = mutableMapOf<String, Int>()

    fun detectPolicy(): GpuPolicyInfo? {
        detectKgsl()?.let { return it }
        val roots = listOf("/sys/class/devfreq", "/sys/devices/platform")
        val candidates = roots.flatMap { root -> fileSystem.listDirectories(root) }.distinct()
        return candidates.asSequence()
            .filter(::isGpuCandidate)
            .filterNot(::isExcluded)
            .mapNotNull(::parseDevfreq)
            .sortedWith(compareBy({ candidateScore(it.policyPath) }, { it.policyPath }))
            .firstOrNull()
    }

    fun readCurrentMaxFrequency(policy: GpuPolicyInfo): Int? = readText(policy.maxFrequencyPath)?.toIntOrNull()

    fun readCurrentClock(policy: GpuPolicyInfo): Int? = policy.currentFrequencyPath
        ?.let(::readText)?.toIntOrNull()

    fun stabilizeObservedCeiling(path: String, candidate: Int): Int = rememberObserved(path, candidate)

    private fun detectKgsl(): GpuPolicyInfo? {
        val path = "/sys/class/kgsl/kgsl-3d0"
        val maxPath = "$path/max_gpuclk"
        val currentPath = "$path/gpuclk"
        val minPath = "$path/min_gpuclk"
        val max = readText(maxPath)?.toIntOrNull() ?: return null
        val available = parseFrequencies(readText("$path/gpu_available_frequencies"))
        val minimum = readText(minPath)?.toIntOrNull()?.takeIf { it > 0 }
        val selectable = available.maxOrNull() ?: max
        val observed = rememberObserved(maxPath, maxOf(max, available.maxOrNull() ?: 0))
        return GpuPolicyInfo(
            policyPath = path,
            maxFrequencyPath = maxPath,
            currentFrequencyPath = currentPath,
            minFrequencyPath = minPath.takeIf { minimum != null },
            currentMaxFrequencyHz = max,
            selectableMaxFrequencyHz = selectable,
            observedMaxFrequencyHz = observed,
            supportedFrequenciesHz = available,
            hardwareMinFrequencyHz = minimum ?: available.minOrNull(),
            minimumCandidatesHz = (listOfNotNull(minimum) + available).distinct().sorted(),
        )
    }

    private fun parseDevfreq(path: String): GpuPolicyInfo? {
        val maxPath = "$path/max_freq"
        val max = readText(maxPath)?.toIntOrNull() ?: return null
        val available = parseFrequencies(readText("$path/available_frequencies"))
        val min = readText("$path/min_freq")?.toIntOrNull()
        val selectable = available.maxOrNull() ?: max
        val observed = rememberObserved(maxPath, maxOf(max, available.maxOrNull() ?: 0))
        val candidates = listOfNotNull(min?.takeIf { it > 0 }) + available.filter { it > 0 }
        return GpuPolicyInfo(
            policyPath = path,
            maxFrequencyPath = maxPath,
            currentFrequencyPath = "$path/cur_freq",
            minFrequencyPath = "$path/min_freq".takeIf { min != null },
            currentMaxFrequencyHz = max,
            selectableMaxFrequencyHz = selectable,
            observedMaxFrequencyHz = observed,
            supportedFrequenciesHz = available,
            hardwareMinFrequencyHz = candidates.minOrNull(),
            minimumCandidatesHz = candidates.distinct().sorted(),
        )
    }

    private fun isExcluded(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return listOf("bus", "busmon", "bw", "memlat").any { name.contains(it) }
    }

    private fun isGpuCandidate(path: String): Boolean {
        val name = path.substringAfterLast('/').lowercase()
        return name.contains("kgsl-3d") || name.contains("gpu") || name.contains("mali")
    }

    private fun candidateScore(path: String): Int = when {
        path.substringAfterLast('/').contains("kgsl-3d") -> 0
        path.substringAfterLast('/').contains("gpu") -> 1
        else -> 2
    }

    internal fun parseFrequencies(raw: String?): List<Int> = raw.orEmpty()
        .split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull()?.takeIf { frequency -> frequency > 0 } }
        .distinct()
        .sorted()

    private fun readText(path: String): String? = fileSystem.readText(path)?.trim()?.takeIf { it.isNotEmpty() }
        ?: privilegedReader.readText(path)?.trim()?.takeIf { it.isNotEmpty() }

    private fun rememberObserved(path: String, candidate: Int): Int {
        synchronized(observedCeilings) {
            val previous = maxOf(observedCeilings[path] ?: 0, ceilingStore.read(path) ?: 0)
            val observed = maxOf(previous, candidate)
            if (observed > 0) observedCeilings[path] = observed
            if (observed > 0) ceilingStore.writeIfHigher(path, observed)
            return observed.takeIf { it > 0 } ?: candidate
        }
    }
}

private object InMemoryGpuCeilingStore : GpuCeilingStore {
    override fun read(path: String): Int? = null
    override fun writeIfHigher(path: String, value: Int) = Unit
}
