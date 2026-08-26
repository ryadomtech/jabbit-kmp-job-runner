package tech.ryadom.jabbit

/**
 * Outcome a worker reports at the end of [JabbitWorker.doWork].
 */
public sealed class JobResult<out O> {

    /**
     * The job finished successfully.
     *
     * A periodic job is scheduled for its next period; a one-time job reaches [JobState.SUCCEEDED]
     * and stops.
     */
    public data class Success<O>(

        /**
         * Payload readable through [JobInfo.output].
         */
        public val output: O
    ) : JobResult<O>()

    /**
     * The job failed permanently and must not be retried.
     *
     * A periodic job that fails stops repeating.
     */
    public data class Failure(

        /**
         * Why the job failed, readable through [JobInfo.failureReason].
         */
        public val reason: String? = null
    ) : JobResult<Nothing>()

    /**
     * The job should run again later, after the backoff delay of its request.
     */
    public data object Retry : JobResult<Nothing>()

    public companion object {

        /**
         * Creates a [Success] result.
         */
        public fun <O> success(output: O): JobResult<O> = Success(output)

        /**
         * Creates a [Success] result for a job that produces nothing.
         */
        public fun success(): JobResult<Unit> = Success(Unit)

        /**
         * Creates a [Failure] result.
         */
        public fun failure(reason: String? = null): JobResult<Nothing> = Failure(reason)

        /**
         * Returns the [Retry] result.
         */
        public fun retry(): JobResult<Nothing> = Retry
    }
}
