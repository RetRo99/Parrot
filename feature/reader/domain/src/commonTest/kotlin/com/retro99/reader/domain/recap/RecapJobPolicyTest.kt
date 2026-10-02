package com.retro99.reader.domain.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class RecapJobPolicyTest {

    @Test
    fun backoffDoublesAndIsCapped() {
        assertEquals(1.minutes, RecapJobPolicy.backoff(1))
        assertEquals(2.minutes, RecapJobPolicy.backoff(2))
        assertEquals(16.minutes, RecapJobPolicy.backoff(5))
        assertEquals(6.hours, RecapJobPolicy.backoff(30))
    }

    @Test
    fun retryAfterWinsWhenLonger() {
        assertEquals(
            1_000L + 2.hours.inWholeMilliseconds,
            RecapJobPolicy.nextAttemptAt(1_000L, attempt = 1, retryAfter = 2.hours),
        )
        assertEquals(
            1_000L + 4.minutes.inWholeMilliseconds,
            RecapJobPolicy.nextAttemptAt(1_000L, attempt = 3, retryAfter = 1.minutes),
        )
    }
}
