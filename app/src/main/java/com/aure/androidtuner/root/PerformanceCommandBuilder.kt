package com.aure.androidtuner.root

import com.aure.androidtuner.model.CpuPolicyInfo

class PerformanceCommandBuilder(
    private val services: List<String> = listOf(
        "perfd",
        "vendor.perf-hal-1-0",
        "vendor.perf-hal-2-0",
    ),
) {

    fun buildApplyScript(
        policies: List<CpuPolicyInfo>,
        selectedValues: Map<Int, Int>,
        isReset: Boolean,
    ): String {
        val lines = mutableListOf<String>()
        if (!isReset) {
            services.forEach { lines += "stop $it" }
        }

        policies.forEach { policy ->
            val value = selectedValues[policy.id] ?: return@forEach
            val targetMode = if (isReset) "644" else "444"
            lines += "chmod 666 ${policy.scalingMaxPath}"
            lines += "echo $value > ${policy.scalingMaxPath}"
            lines += "chmod $targetMode ${policy.scalingMaxPath}"
        }

        if (isReset) {
            services.forEach { lines += "start $it" }
        }

        return buildString {
            appendLine("#!/system/bin/sh")
            lines.forEach(::appendLine)
        }
    }
}
