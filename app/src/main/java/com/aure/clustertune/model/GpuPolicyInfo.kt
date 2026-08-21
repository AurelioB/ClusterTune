package com.aure.clustertune.model

/** Describes the writable GPU frequency cap exposed by a device. Frequencies are Hz. */
data class GpuPolicyInfo(
    val policyPath: String,
    val maxFrequencyPath: String,
    val currentFrequencyPath: String? = null,
    val minFrequencyPath: String? = null,
    val currentMaxFrequencyHz: Int,
    val selectableMaxFrequencyHz: Int,
    val observedMaxFrequencyHz: Int,
    val supportedFrequenciesHz: List<Int> = emptyList(),
    val hardwareMinFrequencyHz: Int? = null,
    val minimumCandidatesHz: List<Int> = emptyList(),
)
