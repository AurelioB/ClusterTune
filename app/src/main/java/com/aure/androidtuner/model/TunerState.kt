package com.aure.androidtuner.model

data class TunerState(
    val isLoading: Boolean = true,
    val isPServerAvailable: Boolean = false,
    val policies: List<CpuPolicyInfo> = emptyList(),
    val actualValues: Map<Int, Int> = emptyMap(),
    val currentValues: Map<Int, Int> = emptyMap(),
    val bundledProfiles: List<PerformanceProfile> = emptyList(),
    val userProfiles: List<PerformanceProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)
