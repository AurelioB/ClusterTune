package com.aure.clustertune.data

import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** History is presentation data: appending an event must not refresh hardware. */
internal fun Flow<TunerState>.withHistory(history: Flow<List<ProfileSwitchHistoryEntry>>): Flow<TunerState> =
    combine(history) { state, entries -> state.copy(profileSwitchHistory = entries) }
