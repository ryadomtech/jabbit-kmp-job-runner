package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.BackoffPolicy
import kotlin.random.Random

private const val MAX_EXPONENT = 30

internal const val BACKOFF_JITTER = 0.2

internal fun backoffDelayMillis(
    policy: BackoffPolicy,
    baseDelayMillis: Long,
    attempt: Int,
    jitter: Double = BACKOFF_JITTER,
    random: Random = Random
): Long {
    val safeAttempt = attempt.coerceAtLeast(1)
    val minMillis = BackoffPolicy.MIN_DELAY.inWholeMilliseconds
    val maxMillis = BackoffPolicy.MAX_DELAY.inWholeMilliseconds
    val base = baseDelayMillis.coerceIn(minMillis, maxMillis)

    val multiplier = when (policy) {
        BackoffPolicy.LINEAR -> safeAttempt.toLong()
        BackoffPolicy.EXPONENTIAL -> 1L shl (safeAttempt - 1).coerceAtMost(MAX_EXPONENT)
    }

    val delay = when {
        base > maxMillis / multiplier -> maxMillis
        else -> (base * multiplier).coerceIn(minMillis, maxMillis)
    }

    return (delay + jitterMillis(delay, jitter, random)).coerceAtMost(maxMillis)
}

private fun jitterMillis(delayMillis: Long, jitter: Double, random: Random): Long {
    val spread = (delayMillis * jitter).toLong()
    return if (spread <= 0) 0L else random.nextLong(spread + 1)
}
