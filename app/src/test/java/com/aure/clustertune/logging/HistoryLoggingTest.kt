package com.aure.clustertune.logging

import com.aure.clustertune.data.ProfileStorageCodec
import com.aure.clustertune.data.resolveNormalProfileIdentity
import com.aure.clustertune.data.withHistory
import com.aure.clustertune.model.HistoryEventType
import com.aure.clustertune.model.ProfileSwitchHistoryEntry
import com.aure.clustertune.model.TunerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HistoryLoggingTest {
    @Test fun identicalFailureIsSuppressedUntilRecoveryOrChange() {
        val gate = HistoryFailureGate()
        assertTrue(gate.accept("detection", "disconnected"))
        repeat(1000) { assertFalse(gate.accept("detection", "disconnected")) }
        assertTrue(gate.accept("detection", "permission denied"))
        gate.recovered("detection")
        assertTrue(gate.accept("detection", "permission denied"))
    }

    @Test fun failureMemoryStaysBounded() {
        val gate = HistoryFailureGate(2)
        assertTrue(gate.accept("one", "failure"))
        assertTrue(gate.accept("two", "failure"))
        assertTrue(gate.accept("three", "failure"))
        assertFalse(gate.accept("two", "failure"))
        assertTrue(gate.accept("one", "failure"))
    }

    @Test fun oldHistoryStillLoadsAndEveryNewEventTypeRoundTrips() {
        val old = """[{"timestampMillis":1,"profileId":"normal","profileName":"Normal","trigger":"Manual"}]"""
        assertTrue(ProfileStorageCodec.parseProfileSwitchHistory(old).single().isProfileSwitch)
        val entries = HistoryEventType.entries.mapIndexed { i, type ->
            ProfileSwitchHistoryEntry(i.toLong(), null, type.label, "Event detail", type, false)
        }
        assertEquals(entries.reversed(), ProfileStorageCodec.parseProfileSwitchHistory(ProfileStorageCodec.encodeProfileSwitchHistory(entries)))
    }

    @Test fun diagnosticsCannotSupplyTheRestoredProfileIdentity() {
        val history = listOf(
            ProfileSwitchHistoryEntry(2, "normal", "Misleading error title", "Failure", HistoryEventType.ERROR, false),
            ProfileSwitchHistoryEntry(1, "normal", "Normal profile", "Manual apply"),
        )
        assertEquals("Normal profile", resolveNormalProfileIdentity("normal", emptyList(), history).profileName)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun historyUpdatesDoNotRestartHardwareReads() = runTest {
        var reads = 0
        val hardware = flow { reads++; emit(TunerState()); awaitCancellation() }
        val history = MutableStateFlow(emptyList<ProfileSwitchHistoryEntry>())
        val results = mutableListOf<TunerState>()
        val job = launch { hardware.withHistory(history).collect { results += it } }
        runCurrent()
        repeat(20) { index ->
            history.value = listOf(ProfileSwitchHistoryEntry(index.toLong(), null, "Event $index", "Detail", HistoryEventType.SERVICE, false))
            runCurrent()
        }
        assertEquals(1, reads)
        assertEquals("Event 19", results.last().profileSwitchHistory.single().profileName)
        job.cancel()
    }

    @Test fun textExportContainsMetadataAndEveryRetainedEntryWithoutModification() {
        val entries = HistoryEventType.entries.mapIndexed { index, type ->
            ProfileSwitchHistoryEntry(index.toLong(), null, "Title ${type.label}", "Reason ${type.label}", type, false)
        }.reversed()
        val before = entries.toList()
        val text = historyExportText(entries, "test-version", "test-device", "13")
        assertTrue(text.contains("App version: test-version"))
        assertTrue(text.contains("Device: test-device"))
        entries.forEach { assertTrue(text.contains("[${it.eventType.label}] ${it.profileName}\n${it.trigger}")) }
        assertTrue(text.indexOf("[Error]") < text.indexOf("[Profile]"))
        assertEquals(before, entries)
    }
}
