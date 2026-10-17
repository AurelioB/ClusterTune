package com.aure.clustertune.root.host

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppHistoryTest {
    @Test fun `only foreground events inside the requested window count`() {
        val history = RecentAppHistory(100, 200)
        history.record("com.example.old", 99, true)
        history.record("com.example.first", 100, true)
        history.record("com.example.last", 199, true)
        history.record("com.example.future", 200, true)
        history.record("com.example.background", 150, false)
        assertEquals(listOf("com.example.last", "com.example.first"), history.packages())
    }

    @Test fun `an older event cannot replace a newer activity timestamp`() {
        val history = RecentAppHistory(0, 1_000)
        history.record("com.example.a", 500, true)
        history.record("com.example.b", 600, true)
        history.record("com.example.b", 200, true)
        history.record("com.example.a", 700, true)
        assertEquals(listOf("com.example.a", "com.example.b"), history.packages())
    }

    @Test fun `malformed or missing packages do not enter the response`() {
        val history = RecentAppHistory(0, 100)
        listOf(null, "", "bad package", "com.example\u0000.bad").forEach { history.record(it, 50, true) }
        assertEquals(emptyList<String>(), history.packages())
    }

    @Test fun `ties are deterministic and package responses are bounded`() {
        val history = RecentAppHistory(0, 100, limit = 2)
        listOf("com.example.c", "com.example.b", "com.example.a").forEach { history.record(it, 50, true) }
        assertEquals(listOf("com.example.a", "com.example.b"), history.packages())
    }

    @Test fun `empty or zero-limit history is usable`() {
        assertEquals(emptyList<String>(), RecentAppHistory(0, 100).packages())
        val history = RecentAppHistory(0, 100, limit = 0)
        history.record("com.example.a", 50, true)
        assertEquals(emptyList<String>(), history.packages())
    }
}
