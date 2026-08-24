package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.randomUuidString
import kotlin.jvm.JvmInline

/**
 * Stable identifier of a single enqueued job.
 *
 * The value is a canonical UUID string. Identifiers are generated automatically when a request is
 * built, but may be supplied explicitly through the request builders when the caller needs to know
 * the identifier before enqueueing.
 */
@JvmInline
public value class JobId(public val value: String) {

    override fun toString(): String = value

    public companion object {

        /**
         * Generates a new random identifier.
         */
        public fun random(): JobId = JobId(randomUuidString())
    }
}
