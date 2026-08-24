package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.BackoffPolicy

private const val MAX_EXPONENT = 30

internal fun backoffDelayMillis(
    policy: BackoffPolicy,
    baseDelayMillis: Long,
    attempt: Int
): Long {
    val safeAttempt = attempt.coerceAtLeast(1)
    val minMillis = BackoffPolicy.MIN_DELAY.inWholeMilliseconds
    val maxMillis = BackoffPolicy.MAX_DELAY.inWholeMilliseconds
    val base = baseDelayMillis.coerceIn(minMillis, maxMillis)

    val multiplier = when (policy) {
        BackoffPolicy.LINEAR -> safeAttempt.toLong()
        BackoffPolicy.EXPONENTIAL -> 1L shl (safeAttempt - 1).coerceAtMost(MAX_EXPONENT)
    }

    if (base > maxMillis / multiplier) return maxMillis
    return (base * multiplier).coerceIn(minMillis, maxMillis)
}
