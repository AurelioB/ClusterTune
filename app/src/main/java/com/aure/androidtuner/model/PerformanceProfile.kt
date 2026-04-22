package com.aure.androidtuner.model

enum class ProfileSource {
    BUNDLED,
    USER,
}

data class PerformanceProfile(
    val id: String,
    val name: String,
    val maxFrequencies: Map<Int, Int>,
    val source: ProfileSource,
    val isResetProfile: Boolean = false,
)
