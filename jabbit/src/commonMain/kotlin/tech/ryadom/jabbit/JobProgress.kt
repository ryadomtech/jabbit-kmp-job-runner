package tech.ryadom.jabbit

import kotlinx.serialization.Serializable

/**
 * What a running job reports about how far along it is.
 *
 * Progress lives in memory only: it is dropped once the job finishes, and an attempt that is cut
 * short starts its next run without it.
 */
@Serializable
public data class JobProgress(

    /**
     * How much of the work is done, from `0.0` to `1.0`, or `null` when the job cannot tell.
     */
    public val fraction: Float? = null,

    /**
     * What the job is doing right now, for showing to a person.
     */
    public val message: String? = null
) {

    init {
        require(fraction == null || fraction in 0f..1f) {
            "fraction must be between 0 and 1, was $fraction"
        }
    }
}
