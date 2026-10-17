package com.aure.clustertune.logging

import android.util.Log
import com.aure.clustertune.data.ProfileStorage
import com.aure.clustertune.data.SettingsStorage
import com.aure.clustertune.model.HistoryEventType
import com.aure.clustertune.model.MAX_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.MIN_PROFILE_SWITCH_HISTORY_LIMIT
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Suppresses identical failures until recovery, with bounded memory and no clock or timer. */
internal class HistoryFailureGate(private val capacity: Int = 32) {
    private val failures = linkedMapOf<String, String>()
    init { require(capacity > 0) }

    @Synchronized fun accept(key: String, message: String): Boolean {
        if (failures[key] == message) return false
        failures.remove(key)
        failures[key] = message
        while (failures.size > capacity) failures.remove(failures.keys.first())
        return true
    }

    @Synchronized fun recovered(key: String) { failures.remove(key) }
}

/** Called by existing events only. Shares History's configured bound; owns no worker or timer. */
class HistoryLogger(private val profiles: ProfileStorage, private val settings: SettingsStorage) {
    suspend fun record(
        type: HistoryEventType,
        title: String,
        detail: String,
    ) = append(ProfileSwitchHistoryEntry(
        timestampMillis = System.currentTimeMillis(),
        profileId = null,
        profileName = title.take(160),
        trigger = detail.take(800),
        eventType = type,
        isProfileSwitch = false,
    ))

    suspend fun failure(key: String, title: String, error: Throwable, detail: String = "") {
        if (error is CancellationException) throw error
        val message = listOf(detail, error.message ?: error.javaClass.simpleName)
            .filter(String::isNotBlank).joinToString(" · ").take(800)
        if (!failureGate.accept(key, "$title: ${error.javaClass.name}: $message")) return
        Log.w(TAG, "$title: $message", error)
        record(HistoryEventType.ERROR, title, message)
    }

    fun recovered(key: String) = failureGate.recovered(key)

    suspend fun append(entry: ProfileSwitchHistoryEntry) {
        try {
            val limit = settings.settings.first().profileSwitchHistoryLimit
                .coerceIn(MIN_PROFILE_SWITCH_HISTORY_LIMIT, MAX_PROFILE_SWITCH_HISTORY_LIMIT)
            profiles.appendProfileSwitchHistory(entry.copy(
                profileName = entry.profileName.take(160), trigger = entry.trigger.take(800),
            ), limit)
            failureGate.recovered("history-storage")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Recording an event must not turn a successful hardware operation into a failure.
            if (failureGate.accept("history-storage", error.message ?: error.javaClass.name)) {
                Log.w(TAG, "Unable to record history event", error)
            }
        }
    }

    private companion object {
        const val TAG = "ClusterTuneHistory"
        val failureGate = HistoryFailureGate()
    }
}
