package com.aure.clustertune.model

data class CpuPolicyInfo(
    val id: Int,
    val policyPath: String,
    val scalingMaxPath: String,
    val currentMaxFreq: Int,
    val selectableMaxFreq: Int,
    val observedMaxFreq: Int,
    val minFreq: Int,
    val supportedFrequencies: List<Int>,
    val cpuIds: List<Int> = listOf(id),
    /** The sysfs minimum-frequency node for this policy. */
    val scalingMinPath: String = "$policyPath/scaling_min_freq",
    /** Lowest advertised candidate used as the validation floor for a policy minimum. */
    val hardwareMinFreq: Int = minFreq,
    /** Ordered minimum values to try when a vendor rejects the nominal floor. */
    val minimumCandidates: List<Int> = listOf(hardwareMinFreq),
)
