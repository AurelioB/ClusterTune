package com.aure.clustertune.model

enum class HistoryEventType(val label: String) {
    PROFILE("Profile"),
    APP("App switch"),
    SLEEP("Sleep"),
    WAKE("Wake"),
    BOOT("Boot"),
    AUTO_TUNE("Auto Tune"),
    SERVICE("Service"),
    ERROR("Error"),
}

data class ProfileSwitchHistoryEntry(
    val timestampMillis: Long,
    val profileId: String?,
    val profileName: String,
    val trigger: String,
    val eventType: HistoryEventType = HistoryEventType.PROFILE,
    val isProfileSwitch: Boolean = true,
)
