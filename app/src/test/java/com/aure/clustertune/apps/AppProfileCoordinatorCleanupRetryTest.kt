package com.aure.clustertune.apps

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProfileCoordinatorCleanupRetryTest {
    @Test
    fun `cleanup retry backs off and succeeds within its bound`() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val outcome = retryAutoTuneCleanupWithBackoff(
            maxAttempts = 3,
            initialDelayMillis = 100L,
            delayBeforeAttempt = { delays += it },
        ) {
            attempts += 1
            if (attempts == 3) {
                AutoTuneCleanupRetryDecision.SUCCESS
            } else {
                AutoTuneCleanupRetryDecision.RETRY
            }
        }

        assertEquals(AutoTuneCleanupRetryOutcome.SUCCEEDED, outcome)
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L, 400L), delays)
    }

    @Test
    fun `cleanup retry abandons immediately when ownership changes`() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0

        val outcome = retryAutoTuneCleanupWithBackoff(
            maxAttempts = 3,
            initialDelayMillis = 100L,
            delayBeforeAttempt = { delays += it },
        ) {
            attempts += 1
            AutoTuneCleanupRetryDecision.ABANDON
        }

        assertEquals(AutoTuneCleanupRetryOutcome.ABANDONED, outcome)
        assertEquals(1, attempts)
        assertEquals(listOf(100L), delays)
    }

    @Test
    fun `cleanup retry stops after the configured attempt limit`() = runTest {
        var attempts = 0

        val outcome = retryAutoTuneCleanupWithBackoff(
            maxAttempts = 2,
            initialDelayMillis = 100L,
            delayBeforeAttempt = {},
        ) {
            attempts += 1
            AutoTuneCleanupRetryDecision.RETRY
        }

        assertEquals(AutoTuneCleanupRetryOutcome.EXHAUSTED, outcome)
        assertEquals(2, attempts)
    }
}
