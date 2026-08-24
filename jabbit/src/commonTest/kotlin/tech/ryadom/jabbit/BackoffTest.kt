package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.backoffDelayMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class BackoffTest {

    private val base = 30.seconds.inWholeMilliseconds

    @Test
    fun exponentialDoublesEveryAttempt() {
        assertEquals(base, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 1))
        assertEquals(base * 2, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 2))
        assertEquals(base * 4, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 3))
    }

    @Test
    fun linearGrowsInEqualSteps() {
        assertEquals(base, backoffDelayMillis(BackoffPolicy.LINEAR, base, attempt = 1))
        assertEquals(base * 2, backoffDelayMillis(BackoffPolicy.LINEAR, base, attempt = 2))
        assertEquals(base * 3, backoffDelayMillis(BackoffPolicy.LINEAR, base, attempt = 3))
    }

    @Test
    fun neverLeavesTheSupportedRange() {
        val max = BackoffPolicy.MAX_DELAY.inWholeMilliseconds
        val min = BackoffPolicy.MIN_DELAY.inWholeMilliseconds

        assertEquals(max, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 40))
        assertEquals(max, backoffDelayMillis(BackoffPolicy.LINEAR, max, attempt = 5))
        assertEquals(min, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, 1, attempt = 1))
    }
}
