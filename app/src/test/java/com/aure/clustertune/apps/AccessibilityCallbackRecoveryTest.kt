package com.aure.clustertune.apps

import org.junit.Assert.*
import org.junit.Test

class AccessibilityCallbackRecoveryTest {
    private class Harness(var fail: Boolean = true) {
        val pending = linkedMapOf<Runnable, Long>()
        val delays = mutableListOf<Long>()
        var reads = 0
        var errors = 0
        val recovery = AccessibilityCallbackRecovery(
            schedule = { task, delay -> pending[task] = delay; delays += delay },
            cancel = { pending.remove(it) },
            onFailure = { errors++ },
            action = { reads++; if (fail) throw SecurityException("window disappeared") },
        )
        fun next() { val task = pending.keys.first(); pending.remove(task); task.run() }
    }

    @Test fun `transient callback failure recovers without another window event`() {
        val h = Harness(); h.recovery.start(); h.recovery.run()
        assertEquals(1, h.errors)
        h.fail = false; h.next()
        assertEquals(2, h.reads)
        assertTrue(h.pending.isEmpty())
    }

    @Test fun `persistent error has only three delayed retries`() {
        val h = Harness(); h.recovery.start(); h.recovery.run()
        repeat(3) { h.next() }
        assertEquals(listOf(250L, 500L, 1000L), h.delays)
        assertEquals(4, h.errors)
        assertTrue(h.pending.isEmpty())
    }

    @Test fun `unbind cancels retry and ignores a callback already dispatched`() {
        val h = Harness(); h.recovery.start(); h.recovery.run()
        val queued = h.pending.keys.single()
        h.recovery.stop(); queued.run(); h.recovery.run()
        assertEquals(1, h.reads)
        assertTrue(h.pending.isEmpty())
    }

    @Test fun `successful later event resets failure budget`() {
        val h = Harness(); h.recovery.start(); h.recovery.run(); repeat(3) { h.next() }
        h.fail = false; h.recovery.run()
        h.fail = true; h.recovery.run()
        assertEquals(250L, h.pending.values.single())
    }

    @Test fun `reconnect resumes work after disconnect`() {
        val h = Harness(false); h.recovery.start(); h.recovery.run(); h.recovery.stop()
        h.recovery.start(); h.recovery.run()
        assertEquals(2, h.reads)
        assertTrue(h.pending.isEmpty())
    }

    @Test fun `new event replaces pending retry instead of duplicating timers`() {
        val h = Harness(); h.recovery.start(); h.recovery.run(); h.recovery.run()
        assertEquals(1, h.pending.size)
        h.fail = false; h.recovery.run()
        assertTrue(h.pending.isEmpty())
    }

    @Test fun `fatal VM errors are not swallowed as recoverable window failures`() {
        val recovery = AccessibilityCallbackRecovery({ _, _ -> fail("must not retry") }, {}, {}, { throw OutOfMemoryError() })
        recovery.start()
        assertThrows(OutOfMemoryError::class.java) { recovery.run() }
    }
}
