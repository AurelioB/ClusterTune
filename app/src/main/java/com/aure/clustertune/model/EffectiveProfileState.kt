package com.aure.clustertune.model

/** Lightweight, persisted description of the profile currently applied to the device. */
enum class EffectiveProfileSource { NORMAL, APP, SLEEP, COMBINED, MANUAL, STOCK }

data class EffectiveProfileState(
    val id: String,
    val name: String,
    val source: EffectiveProfileSource,
    val contributingPackageNames: List<String> = emptyList(),
    val timestampMillis: Long = 0L,
    val generation: Long = 0L,
)
