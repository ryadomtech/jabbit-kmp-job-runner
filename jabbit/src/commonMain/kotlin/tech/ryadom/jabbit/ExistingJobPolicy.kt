package tech.ryadom.jabbit

/**
 * How [Jabbit.enqueueUnique] resolves a collision with an unfinished job of the same unique name.
 */
public enum class ExistingJobPolicy {

    /**
     * Keeps the existing job and drops the new request.
     */
    KEEP,

    /**
     * Drops the existing job and enqueues the new request.
     *
     * The replaced job stops being observable: its identifier is forgotten rather than left behind
     * as [JobState.CANCELLED]. A run already in progress is cancelled cooperatively.
     */
    REPLACE
}

/**
 * How [Jabbit.enqueueUniquePeriodic] resolves a collision with an unfinished periodic job of the
 * same unique name.
 */
public enum class ExistingPeriodicJobPolicy {

    /**
     * Keeps the existing job and drops the new request.
     */
    KEEP,

    /**
     * Applies the new request to the existing job without restarting its period, so a job that
     * already waited most of its interval keeps that progress.
     */
    UPDATE,

    /**
     * Drops the existing job and enqueues the new request, restarting the period.
     *
     * The replaced job stops being observable: its identifier is forgotten rather than left behind
     * as [JobState.CANCELLED]. A run already in progress is cancelled cooperatively.
     */
    CANCEL_AND_REENQUEUE
}
