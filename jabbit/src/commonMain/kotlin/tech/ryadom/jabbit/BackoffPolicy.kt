package tech.ryadom.jabbit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How the delay before a retry grows after a job returns [JobResult.Retry] or is interrupted.
 *
 * Whatever the policy computes, the delay is stretched by a random amount of up to a fifth on top,
 * so that jobs which failed together — a network outage usually takes them all down at once — come
 * back spread out instead of hammering the same second.
 */
public enum class BackoffPolicy {

    /**
     * The delay doubles on every attempt: `delay * 2^(attempt - 1)`.
     */
    EXPONENTIAL,

    /**
     * The delay grows in equal steps: `delay * attempt`.
     */
    LINEAR;

    public companion object {

        /**
         * Delay applied to the first retry unless the request overrides it.
         */
        public val DEFAULT_DELAY: Duration = 30.seconds

        /**
         * Lower bound the platforms enforce on the retry delay.
         */
        public val MIN_DELAY: Duration = 10.seconds

        /**
         * Upper bound the platforms enforce on the retry delay.
         */
        public val MAX_DELAY: Duration = 5.hours
    }
}
