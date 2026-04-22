package com.aure.androidtuner.model

data class CpuPolicyInfo(
    val id: Int,
    val policyPath: String,
    val scalingMaxPath: String,
    val currentMaxFreq: Int,
    val stockMaxFreq: Int,
    val minFreq: Int,
    val supportedFrequencies: List<Int>,
)
