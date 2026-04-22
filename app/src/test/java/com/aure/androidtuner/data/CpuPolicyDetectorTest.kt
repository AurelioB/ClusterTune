package com.aure.androidtuner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuPolicyDetectorTest {

    @Test
    fun `detects and sorts policies from sysfs`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policy6",
                "/sys/devices/system/cpu/cpufreq/policy0",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq" to "2745600",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq" to "3532800",
                "/sys/devices/system/cpu/cpufreq/policy0/cpuinfo_min_freq" to "998400",
                "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies" to "998400 1785600 2227200 2745600 3532800",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq" to "3072000",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq" to "4320000",
                "/sys/devices/system/cpu/cpufreq/policy6/cpuinfo_min_freq" to "1075200",
                "/sys/devices/system/cpu/cpufreq/policy6/scaling_available_frequencies" to "1075200 1958400 2246400 3072000 4320000",
            ),
        )

        val detector = CpuPolicyDetector(fileSystem)

        val result = detector.detectPolicies()

        assertEquals(listOf(0, 6), result.map { it.id })
        assertEquals(listOf(998400, 1785600, 2227200, 2745600, 3532800), result.first().supportedFrequencies)
        assertEquals(4320000, result.last().stockMaxFreq)
    }

    @Test
    fun `falls back when scaling_available_frequencies is missing`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf("/sys/devices/system/cpu/cpufreq/policy2"),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_max_freq" to "2100000",
                "/sys/devices/system/cpu/cpufreq/policy2/cpuinfo_max_freq" to "2500000",
                "/sys/devices/system/cpu/cpufreq/policy2/scaling_min_freq" to "800000",
            ),
        )

        val detector = CpuPolicyDetector(fileSystem)

        val result = detector.detectPolicies().single()

        assertEquals(listOf(800000, 2100000, 2500000), result.supportedFrequencies)
    }

    @Test
    fun `ignores malformed policy directories`() {
        val fileSystem = FakeSysfsFileSystem(
            directories = listOf(
                "/sys/devices/system/cpu/cpufreq/policyX",
                "/sys/devices/system/cpu/cpufreq/policy1",
            ),
            files = mapOf(
                "/sys/devices/system/cpu/cpufreq/policy1/scaling_max_freq" to "1500000",
                "/sys/devices/system/cpu/cpufreq/policy1/cpuinfo_max_freq" to "2000000",
                "/sys/devices/system/cpu/cpufreq/policy1/cpuinfo_min_freq" to "500000",
            ),
        )

        val detector = CpuPolicyDetector(fileSystem)

        val result = detector.detectPolicies()

        assertEquals(1, result.size)
        assertTrue(result.all { it.id == 1 })
    }

    private class FakeSysfsFileSystem(
        private val directories: List<String>,
        private val files: Map<String, String>,
    ) : SysfsFileSystem {
        override fun listPolicyDirectories(root: String): List<String> = directories
        override fun readText(path: String): String? = files[path]
    }
}
