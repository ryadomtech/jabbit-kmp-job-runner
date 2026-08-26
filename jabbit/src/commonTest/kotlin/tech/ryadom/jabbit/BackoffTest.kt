package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.BACKOFF_JITTER
import tech.ryadom.jabbit.internal.backoffDelayMillis
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BackoffTest {

    private val base = 30.seconds.inWholeMilliseconds

    private fun delayWithoutJitter(policy: BackoffPolicy, attempt: Int): Long =
        backoffDelayMillis(policy, base, attempt, jitter = 0.0)

    @Test
    fun exponentialDoublesEveryAttempt() {
        assertEquals(base, delayWithoutJitter(BackoffPolicy.EXPONENTIAL, attempt = 1))
        assertEquals(base * 2, delayWithoutJitter(BackoffPolicy.EXPONENTIAL, attempt = 2))
        assertEquals(base * 4, delayWithoutJitter(BackoffPolicy.EXPONENTIAL, attempt = 3))
    }

    @Test
    fun linearGrowsInEqualSteps() {
        assertEquals(base, delayWithoutJitter(BackoffPolicy.LINEAR, attempt = 1))
        assertEquals(base * 2, delayWithoutJitter(BackoffPolicy.LINEAR, attempt = 2))
        assertEquals(base * 3, delayWithoutJitter(BackoffPolicy.LINEAR, attempt = 3))
    }

    @Test
    fun neverLeavesTheSupportedRange() {
        val max = BackoffPolicy.MAX_DELAY.inWholeMilliseconds
        val min = BackoffPolicy.MIN_DELAY.inWholeMilliseconds

        assertEquals(max, backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 40))
        assertEquals(max, backoffDelayMillis(BackoffPolicy.LINEAR, max, attempt = 5))
        assertTrue(backoffDelayMillis(BackoffPolicy.EXPONENTIAL, 1, attempt = 1) >= min)
    }

    @Test
    fun neverRetriesEarlierThanThePolicyAsks() {
        val random = Random(seed = 1)

        repeat(200) {
            val delay = backoffDelayMillis(BackoffPolicy.LINEAR, base, attempt = 2, random = random)

            assertTrue(delay >= base * 2, "jitter must not pull a retry forward, got $delay")
            assertTrue(
                delay <= (base * 2 * (1 + BACKOFF_JITTER)).toLong(),
                "jitter must stay within a fifth, got $delay"
            )
        }
    }

    @Test
    fun spreadsRetriesThatWouldOtherwiseCollide() {
        val random = Random(seed = 2)
        val delays = (1..50)
            .map {
                backoffDelayMillis(BackoffPolicy.EXPONENTIAL, base, attempt = 3, random = random)
            }
            .toSet()

        assertTrue(delays.size > 1, "every retry landed on the same millisecond")
    }
}
