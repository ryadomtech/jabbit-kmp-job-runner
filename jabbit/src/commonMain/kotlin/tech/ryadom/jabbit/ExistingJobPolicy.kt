package tech.ryadom.jabbit

/**
 * How [Jabbit.enqueueUnique] resolves a collision with an unfinished job of the same unique name.
 */
public enum class ExistingJobPolicy {

    /** Keeps the existing job and drops the new request. */
    KEEP,

    /** Cancels the existing job and enqueues the new request. */
    REPLACE
}

/**
 * How [Jabbit.enqueueUniquePeriodic] resolves a collision with an unfinished periodic job of the
 * same unique name.
 */
public enum class ExistingPeriodicJobPolicy {

    /** Keeps the existing job and drops the new request. */
    KEEP,

    /**
     * Applies the new request to the existing job without restarting its period, so a job that
     * already waited most of its interval keeps that progress.
     */
    UPDATE,

    /**
     * Cancels the existing job and enqueues the new request, restarting the period.
     */
    CANCEL_AND_REENQUEUE
}
