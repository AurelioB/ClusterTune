package com.aure.clustertune.boot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRestoreRetryTest {
    @Test
    fun retriesTransientFailuresUntilRestoreSucceeds() = runBlocking {
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()

        val result = retryBootRestore(
            attemptDelaysMs = listOf(0L, 100L, 250L),
            wait = waits::add,
        ) { attempt ->
            attempts += attempt
            if (attempt < 3) Result.failure(IllegalStateException("host unavailable"))
            else Result.success("restored")
        }

        assertEquals("restored", result.getOrThrow())
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(100L, 250L), waits)
    }

    @Test
    fun returnsFinalFailureAfterBoundedAttempts() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = retryBootRestore<Unit>(
            attemptDelaysMs = listOf(0L, 1L, 2L),
            wait = {},
        ) { attempt ->
            attempts += attempt
            Result.failure(IllegalStateException("failure $attempt"))
        }

        assertTrue(result.isFailure)
        assertEquals("failure 3", result.exceptionOrNull()?.message)
        assertEquals(listOf(1, 2, 3), attempts)
    }
}
