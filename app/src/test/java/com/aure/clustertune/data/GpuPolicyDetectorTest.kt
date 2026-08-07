package com.aure.clustertune.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuPolicyDetectorTest {
    @Test fun `GPU ceiling preference key is device scoped`() {
        assertEquals("device-a|/sys/gpu/max", gpuCeilingPreferenceKey("device-a", "/sys/gpu/max"))
        assertEquals("device-b|/sys/gpu/max", gpuCeilingPreferenceKey("device-b", "/sys/gpu/max"))
    }

    @Test fun `prefers kgsl policy`() {
        val values = mapOf(
            "/sys/class/kgsl/kgsl-3d0/max_gpuclk" to "800000000",
            "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies" to "200000000 400000000 800000000",
        )
        val policy = detector(values, listOf("/sys/class/devfreq/gpu0")).detectPolicy()
        assertEquals("/sys/class/kgsl/kgsl-3d0/max_gpuclk", policy?.maxFrequencyPath)
        assertEquals(800000000, policy?.selectableMaxFrequencyHz)
    }

    @Test fun `generic detector excludes bus and deduplicates frequencies`() {
        val values = mapOf(
            "/sys/class/devfreq/gpu0/max_freq" to "600",
            "/sys/class/devfreq/gpu0/available_frequencies" to "400 600 400",
        )
        val policy = detector(values, listOf("/sys/class/devfreq/bus0", "/sys/class/devfreq/gpu0")).detectPolicy()
        assertEquals("/sys/class/devfreq/gpu0", policy?.policyPath)
        assertEquals(null, policy?.minFrequencyPath)
        assertEquals(listOf(400, 600), policy?.supportedFrequenciesHz)
        assertEquals(listOf(400, 600), policy?.minimumCandidatesHz)
    }

    @Test fun `keeps full available ceiling when current cap is lower`() {
        val values = mapOf(
            "/sys/class/devfreq/gpu0/max_freq" to "400",
            "/sys/class/devfreq/gpu0/available_frequencies" to "200 400 800",
        )
        val policy = detector(values, listOf("/sys/class/devfreq/gpu0")).detectPolicy()!!
        assertEquals(400, policy.currentMaxFrequencyHz)
        assertEquals(800, policy.selectableMaxFrequencyHz)
        assertEquals(800, policy.observedMaxFrequencyHz)
    }

    @Test fun `keeps observed ceiling when enumeration disappears after a cap`() {
        val values = mutableMapOf<String, String>(
            "/sys/class/devfreq/gpu0/max_freq" to "800",
            "/sys/class/devfreq/gpu0/available_frequencies" to "200 400 800",
        )
        val detector = GpuPolicyDetector(
            fileSystem = object : SysfsFileSystem {
                override fun listPolicyDirectories(root: String) = emptyList<String>()
                override fun listDirectories(root: String) = listOf("/sys/class/devfreq/gpu0")
                override fun readText(path: String) = values[path]
            },
            privilegedReader = PrivilegedSysfsReader { values[it] },
            privilegedLister = PrivilegedSysfsLister { _, prefix ->
                listOf("/sys/class/devfreq/gpu0").filter { it.substringAfterLast('/').startsWith(prefix) }
            },
        )
        assertEquals(800, detector.detectPolicy()?.observedMaxFrequencyHz)
        values["/sys/class/devfreq/gpu0/max_freq"] = "400"
        values.remove("/sys/class/devfreq/gpu0/available_frequencies")
        assertEquals(800, detector.detectPolicy()?.observedMaxFrequencyHz)
    }

    @Test fun `restored detector uses persisted ceiling when enumeration is absent`() {
        val values = mutableMapOf<String, String>(
            "/sys/class/devfreq/gpu0/max_freq" to "800",
            "/sys/class/devfreq/gpu0/available_frequencies" to "200 400 800",
        )
        val persisted = mutableMapOf<String, Int>()
        fun make() = GpuPolicyDetector(
            fileSystem = object : SysfsFileSystem {
                override fun listPolicyDirectories(root: String) = emptyList<String>()
                override fun listDirectories(root: String) = listOf("/sys/class/devfreq/gpu0")
                override fun readText(path: String) = values[path]
            },
            privilegedReader = PrivilegedSysfsReader { values[it] },
            privilegedLister = PrivilegedSysfsLister { _, prefix -> listOf("/sys/class/devfreq/gpu0").filter { it.substringAfterLast('/').startsWith(prefix) } },
            ceilingStore = object : GpuCeilingStore {
                override fun read(path: String) = persisted[path]
                override fun writeIfHigher(path: String, value: Int) { persisted[path] = maxOf(persisted[path] ?: 0, value) }
            },
        )
        assertEquals(800, make().detectPolicy()?.observedMaxFrequencyHz)
        values["/sys/class/devfreq/gpu0/max_freq"] = "400"
        values.remove("/sys/class/devfreq/gpu0/available_frequencies")
        assertEquals(800, make().detectPolicy()?.observedMaxFrequencyHz)
    }

    private fun detector(values: Map<String, String>, dirs: List<String>) = GpuPolicyDetector(
        fileSystem = object : SysfsFileSystem {
            override fun listPolicyDirectories(root: String) = emptyList<String>()
            override fun listDirectories(root: String) = dirs
            override fun readText(path: String) = values[path]
        },
        privilegedReader = PrivilegedSysfsReader { values[it] },
        privilegedLister = PrivilegedSysfsLister { _, prefix -> dirs.filter { it.substringAfterLast('/').startsWith(prefix) } },
    )
}
