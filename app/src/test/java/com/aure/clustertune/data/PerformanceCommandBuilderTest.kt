package com.aure.clustertune.data

import com.aure.clustertune.model.CpuPolicyInfo
import com.aure.clustertune.root.PerformanceCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            selectableMaxFreq = 3_532_800,
            observedMaxFreq = 3_532_800,
            minFreq = 1_500_000,
            supportedFrequencies = listOf(1_500_000, 2_745_600, 3_532_800),
            hardwareMinFreq = 998_400,
            minimumCandidates = listOf(998_400),
        ),
        CpuPolicyInfo(
            id = 6,
            policyPath = "/sys/devices/system/cpu/cpufreq/policy6",
            scalingMaxPath = "/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq",
            currentMaxFreq = 3_072_000,
            selectableMaxFreq = 4_320_000,
            observedMaxFreq = 4_320_000,
            minFreq = 1_075_200,
            supportedFrequencies = listOf(1_075_200, 3_072_000, 4_320_000),
        ),
    )

    @Test
    fun `repairs minimum before writing maximum and avoids hardcoded 666`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        assertTrue(script.contains("for candidate in 998400"))
        assertTrue(script.contains("echo \"\$candidate\" > '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'"))
        assertTrue(script.contains("chmod u+w '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'"))
        assertTrue(script.contains("chmod a-w '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertFalse(script.contains("chmod 666"))
        assertTrue(
            script.indexOf("scaling_min_freq") <
                script.indexOf("echo '2745600' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"),
        )
        assertFalse(script.contains("printf '%s\\n' '2745600' >"))
        assertFalse(script.contains("printf '%s\\n' \"\$candidate\" >"))
    }

    @Test
    fun `repairs every policy minimum before any policy maximum`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        val policy0Min = script.indexOf("scaling_min_freq'", startIndex = 0)
        val policy6Min = script.indexOf("policy6/scaling_min_freq")
        val policy0Max = script.indexOf("policy0/scaling_max_freq")
        val policy6Max = script.indexOf("policy6/scaling_max_freq")

        assertTrue(policy0Min >= 0)
        assertTrue(policy6Min > policy0Min)
        assertTrue(policy0Max > policy6Min)
        assertTrue(policy6Max > policy0Max)
    }

    @Test
    fun `underclock keeps maximum locked while preserving original mode on failure`() {
        val script = builder.buildApplyScript(policies, mapOf(0 to 2_745_600, 6 to 3_072_000), false)
        val maxCommand = script.substringAfter("mode=\$(stat -c %a '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq')")
        assertTrue(maxCommand.contains("chmod \"\$mode\" '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertTrue(maxCommand.contains("Failed to write 2745600 to /sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq"))
        assertTrue(maxCommand.contains("chmod a-w '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
    }

    @Test
    fun `minimum repair keeps node writable when every candidate is rejected`() {
        val script = builder.buildMinimumRepairScript(policies)
        val command = script.substringAfter("stat -c %a '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'")

        assertTrue(command.contains("for candidate in 998400"))
        assertTrue(command.contains("if echo \"\$candidate\" > '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq' 2>/dev/null; then break; fi"))
        assertTrue(command.contains("chmod u+w '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'"))
        assertFalse(command.contains("chmod \"\$mode\""))
        assertTrue(script.trimEnd().endsWith("printf '%s\\n' 'clustertune-script-complete'"))
    }

    @Test
    fun `stock reset leaves maximum writable`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 3_532_800, 6 to 4_320_000),
            isReset = true,
        )

        assertTrue(script.contains("chmod u+w '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertFalse(script.contains("chmod a-w '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertFalse(script.contains("start "))
        assertFalse(script.contains("stop "))
    }

    @Test
    fun `stock reset writes selectable ceiling when observed stock is a hidden bin`() {
        val hiddenStockPolicy = policies.first().copy(
            selectableMaxFreq = 3_072_000,
            observedMaxFreq = 3_187_200,
        )

        val script = builder.buildApplyScript(
            policies = listOf(hiddenStockPolicy),
            selectedValues = mapOf(0 to 3_187_200),
            isReset = true,
        )

        assertTrue(script.contains("echo '3072000' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertFalse(script.contains("echo '3187200' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
    }

    @Test
    fun `non stock cap remains exact when it is a selectable frequency`() {
        val script = builder.buildApplyScript(
            policies = policies,
            selectedValues = mapOf(0 to 2_745_600, 6 to 3_072_000),
            isReset = false,
        )

        assertTrue(script.contains("echo '2745600' > '/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq'"))
        assertTrue(script.contains("echo '3072000' > '/sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq'"))
    }

    @Test
    fun `script emits completion marker after all writes`() {
        val script = builder.buildApplyScript(policies, mapOf(0 to 2_745_600, 6 to 3_072_000), false)
        assertTrue(script.trimEnd().endsWith("printf '%s\\n' 'clustertune-script-complete'"))
    }

    @Test
    fun `rejects a maximum below hardware floor`() {
        val error = runCatching {
            builder.buildApplyScript(policies, mapOf(0 to 900_000, 6 to 3_072_000), false)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
    }

    @Test
    fun `tries ordered minimum candidates until a higher value is accepted`() {
        val policy = policies.first().copy(
            hardwareMinFreq = 595_200,
            minimumCandidates = listOf(595_200, 729_600, 864_000),
        )

        val script = builder.buildApplyScript(
            policies = listOf(policy),
            selectedValues = mapOf(0 to 1_000_000),
            isReset = false,
        )

        assertTrue(script.contains("for candidate in 595200 729600 864000"))
        assertTrue(script.contains("2>/dev/null; then break; fi"))
        assertTrue(script.contains("chmod u+w '/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq'"))
    }

    @Test
    fun `minimum repair excludes candidates above current maximum`() {
        val policy = policies.first().copy(
            currentMaxFreq = 800_000,
            hardwareMinFreq = 595_200,
            minimumCandidates = listOf(595_200, 729_600, 864_000),
        )

        val script = builder.buildMinimumRepairScript(listOf(policy))

        assertTrue(script.contains("for candidate in 595200 729600"))
        assertFalse(script.contains("for candidate in 595200 729600 864000"))
    }
}
