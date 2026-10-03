package com.aure.clustertune.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TransientReadRecoveryTest {
    @Test fun `transient read resubscribes and delivers current data`() = runTest {
        var reads = 0; var retries = 0
        val values = flow {
            if (++reads < 3) throw IOException("temporary storage error")
            emit("current assignment")
        }.retryTransientReads { retries++ }.toList()
        assertEquals(listOf("current assignment"), values)
        assertEquals(2, retries)
        assertEquals(750L, testScheduler.currentTime)
    }

    @Test fun `persistent IO error remains visible after bounded retries`() = runTest {
        var attempts = 0
        val failure = runCatching {
            flow<Unit> { attempts++; throw IOException("unavailable") }.retryTransientReads {}.toList()
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(4, attempts)
        assertEquals(1750L, testScheduler.currentTime)
    }

    @Test fun `programming errors do not trigger storage retries`() = runTest {
        val failure = runCatching {
            flow<Unit> { throw IllegalStateException("bad configuration") }
                .retryTransientReads { fail("must not retry") }.toList()
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test fun `cancellation is never converted to recovery`() = runTest {
        val failure = runCatching {
            flow<Unit> { throw CancellationException("service stopped") }
                .retryTransientReads { fail("must not retry") }.toList()
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
}
