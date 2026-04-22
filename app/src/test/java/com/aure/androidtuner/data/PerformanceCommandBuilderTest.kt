package com.aure.androidtuner.data

import com.aure.androidtuner.model.CpuPolicyInfo
import com.aure.androidtuner.root.PerformanceCommandBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceCommandBuilderTest {

    private val builder = PerformanceCommandBuilder()
    private val policies = listOf(
        CpuPolicyInfo(
            id = 0,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy0",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq",
            currentMaxFreq = 2_745_600,
            stockMaxFreq = 3_532_800,
            minFreq = 998_400,
            supportedFrequencies = listOf(998_400, 2_745_600, 3_532_800),
        ),
        CpuPolicyInfo(
            id = 6,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy6",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            currentMaxFreq = 3_072_000,
            stockMaxFreq = 4_320_000,
            minFreq = 1_075_200,
            supportedFrequencies = listOf(1_075_200, 3_072_000, 4_320_000),
        ),
    )

    @Test
    fun `builds underclock script with service stop`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        assertTrue(script.contains("stop perfd"))
        assertTrue(script.contains("echo 2745600 > /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("chmod 444 /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq"))
    }

    @Test
    fun `builds reset script with service restart`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 3_532_800, 6 to 4_320_000),
            isReset = true,
        )

        assertTrue(script.contains("chmod 644 /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(script.contains("start vendor.perf-hal-1-0"))
        assertTrue(!script.contains("stop perfd"))
    }
}
