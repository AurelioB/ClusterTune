package com.aure.clustertune.root.host

import android.os.Binder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HostAppMonitorRecoveryTest {
    private val window = HostAppWindow("com.example.game", 4, true, 100L)

    @Test fun healthyIdleDoesNotPollApps() {
        val reads = AtomicInteger()
        val queue = LinkedBlockingQueue<HostAppObservation>()
        val monitor = HostAppMonitor(10_123) { object : HostTaskSource {
            override fun start() {}
            override fun close() {}
            override fun read(ownerUser: Int): List<HostAppWindow> {
                reads.incrementAndGet()
                return listOf(window)
            }
        } }
        try {
            monitor.subscribe(callback(queue))
            assertNotNull(queue.poll(3, TimeUnit.SECONDS))
            Thread.sleep(1_100) // Allow the finite transition-settling samples to finish.
            val settledAt = reads.get()
            Thread.sleep(1_100)
            assertEquals("idle app detection must be event driven", settledAt, reads.get())
        } finally { monitor.close() }
    }

    @Test fun persistentReadFailuresStopRetryingAndANewEventCanRecover() {
        val failing = AtomicBoolean(true)
        val reads = AtomicInteger()
        val queue = LinkedBlockingQueue<HostAppObservation>()
        var changed: () -> Unit = {}
        val monitor = HostAppMonitor(10_123) { notify ->
            changed = notify
            object : HostTaskSource {
                override fun start() {}
                override fun close() {}
                override fun read(ownerUser: Int): List<HostAppWindow> {
                    reads.incrementAndGet()
                    check(!failing.get()) { "persistent read failure" }
                    return listOf(window)
                }
            }
        }
        try {
            monitor.subscribe(callback(queue))
            assertNotNull(queue.poll(3, TimeUnit.SECONDS)?.error)
            Thread.sleep(2_500)
            val exhaustedAt = reads.get()
            Thread.sleep(1_100)
            assertEquals(exhaustedAt, reads.get())
            queue.clear()
            failing.set(false)
            changed()
            assertEquals(listOf(window), queue.poll(3, TimeUnit.SECONDS)?.windows)
        } finally { monitor.close() }
    }

    @Test fun aBlockedQueryDoesNotPreventUnsubscribeOrPublishAfterItWasRetired() {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = LinkedBlockingQueue<HostAppObservation>()
        val monitor = HostAppMonitor(10_123) { object : HostTaskSource {
            override fun start() {}
            override fun close() {}
            override fun read(ownerUser: Int): List<HostAppWindow> {
                reading.countDown()
                release.await(5, TimeUnit.SECONDS)
                return listOf(window)
            }
        } }
        try {
            val callback = callback(queue)
            monitor.subscribe(callback)
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            val start = System.nanoTime()
            monitor.unsubscribe(callback)
            assertTrue("unsubscribe waited for the query", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1))
            release.countDown()
            assertNull(queue.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            monitor.close()
        }
    }

    @Test fun transientReadFailureClearsOwnershipAndRecoversWithoutAnotherEvent() {
        val attempts = AtomicInteger()
        val queue = LinkedBlockingQueue<HostAppObservation>()
        val source = object : HostTaskSource {
            override fun start() {}
            override fun close() {}
            override fun read(ownerUser: Int): List<HostAppWindow> {
                assertEquals(3, ownerUser)
                if (attempts.incrementAndGet() == 1) throw SecurityException("disappearing task")
                return listOf(window)
            }
        }
        val monitor = HostAppMonitor(300_123) { source }
        try {
            monitor.subscribe(callback(queue))
            val failure = requireNotNull(queue.poll(3, TimeUnit.SECONDS))
            assertNotNull(failure.error)
            assertTrue(failure.windows.isEmpty())
            val recovered = requireNotNull(queue.poll(3, TimeUnit.SECONDS))
            assertNull(recovered.error)
            assertEquals(listOf(window), recovered.windows)
        } finally { monitor.close() }
    }

    @Test fun unsubscribeCancelsSettlingSamplesAndUnregistersFrameworkListener() {
        val reads = AtomicInteger()
        var unregistered = false
        val queue = LinkedBlockingQueue<HostAppObservation>()
        val source = object : HostTaskSource {
            override fun start() {}
            override fun close() { unregistered = true }
            override fun read(ownerUser: Int): List<HostAppWindow> {
                reads.incrementAndGet()
                return listOf(window)
            }
        }
        val monitor = HostAppMonitor(10_123) { source }
        try {
            val callback = callback(queue)
            monitor.subscribe(callback)
            assertNotNull(queue.poll(3, TimeUnit.SECONDS))
            monitor.unsubscribe(callback)
            assertTrue(unregistered)
            val stoppedAt = reads.get()
            Thread.sleep(1_100)
            assertEquals(stoppedAt, reads.get())
        } finally { monitor.close() }
    }

    @Test fun deniedRegistrationIsReportedAndCleansUpTheAttemptedListener() {
        var closed = false
        val monitor = HostAppMonitor(10_123) { object : HostTaskSource {
            override fun start() { throw SecurityException("denied by firmware") }
            override fun close() { closed = true }
            override fun read(ownerUser: Int) = emptyList<HostAppWindow>()
        } }
        try {
            assertTrue(runCatching { monitor.subscribe(Binder()) }.exceptionOrNull() is SecurityException)
            assertTrue(closed)
        } finally { monitor.close() }
    }

    @Test fun wireRejectsOversizedSnapshotsAndInvalidDisplayIds() {
        val data = Parcel.obtain()
        try {
            data.writeString(null)
            data.writeInt(HostProtocol.MAX_APP_WINDOWS + 1)
            data.setDataPosition(0)
            assertTrue(runCatching { HostAppObservationWire.read(data) }.isFailure)
            data.setDataSize(0)
            data.setDataPosition(0)
            HostAppObservationWire.write(data, HostAppObservation(listOf(window.copy(displayId = -1))))
            data.setDataPosition(0)
            assertTrue(runCatching { HostAppObservationWire.read(data) }.isFailure)
        } finally { data.recycle() }
    }

    private fun callback(queue: LinkedBlockingQueue<HostAppObservation>) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(HostProtocol.APP_WINDOWS_DESCRIPTOR)
            assertEquals(HostProtocol.VERSION, data.readInt())
            queue.offer(HostAppObservationWire.read(data))
            return true
        }
    }
}
