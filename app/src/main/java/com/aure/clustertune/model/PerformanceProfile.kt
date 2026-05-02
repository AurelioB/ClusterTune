package com.aure.clustertune.model

enum class ProfileSource {
    BUNDLED,
    USER,
    VIRTUAL,
}

data class PerformanceProfile(
    val id: String,
    val name: String,
    val maxFrequencies: Map<Int, Int>,
    val source: ProfileSource,
    val isResetProfile: Boolean = false,
    val order: Int = 0,
    val isEditable: Boolean = source == ProfileSource.USER,
    val isDeletable: Boolean = source == ProfileSource.USER,
)
