package com.aure.clustertune.root.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class HostDeadlineTaskTest {
    @Test fun `idle cancelled and closed tasks have no pending timer`() {
        val calls = AtomicInteger()
        HostDeadlineTask(Any(), Runnable { calls.incrementAndGet() }).use { timer ->
            assertFalse(timer.isScheduled)
            timer.scheduleAt(null)
            assertFalse(timer.isScheduled)
            timer.scheduleAt(System.nanoTime() + TimeUnit.MINUTES.toNanos(1))
            assertTrue(timer.isScheduled)
            timer.scheduleAt(null)
            assertFalse(timer.isScheduled)
            timer.close()
            timer.scheduleAt(System.nanoTime())
            assertFalse(timer.isScheduled)
            assertEquals(0, calls.get())
        }
    }

    @Test fun `an earlier deadline replaces a later task and runs only once`() {
        val fired = CountDownLatch(1)
        val calls = AtomicInteger()
        val monitor = Any()
        HostDeadlineTask(monitor, Runnable { calls.incrementAndGet(); fired.countDown() }).use { timer ->
            synchronized(monitor) {
                timer.scheduleAt(System.nanoTime() + TimeUnit.MINUTES.toNanos(1))
                timer.scheduleAt(System.nanoTime())
            }
            assertTrue(fired.await(2, TimeUnit.SECONDS))
            synchronized(monitor) {
                assertFalse(timer.isScheduled)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun `a deadline callback can rearm itself for renewed session expiry`() {
        val fired = CountDownLatch(1)
        val calls = AtomicInteger()
        lateinit var timer: HostDeadlineTask
        timer = HostDeadlineTask(Any(), Runnable {
            if (calls.incrementAndGet() == 1) timer.scheduleAt(System.nanoTime())
            else fired.countDown()
        })
        timer.use {
            timer.scheduleAt(System.nanoTime())
            assertTrue(fired.await(2, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        }
    }
}
