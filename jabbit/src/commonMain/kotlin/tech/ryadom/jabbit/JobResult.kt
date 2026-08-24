package tech.ryadom.jabbit

/**
 * Outcome a worker reports at the end of [JabbitWorker.doWork].
 */
public sealed class JobResult {

    /**
     * The job finished successfully.
     *
     * A periodic job is scheduled for its next period; a one-time job reaches
     * [JobState.SUCCEEDED] and stops.
     */
    public data class Success(

        /**
         * Payload exposed through [JobInfo.outputData].
         */
        public val outputData: JobData = JobData.EMPTY
    ) : JobResult()

    /**
     * The job failed permanently and must not be retried.
     *
     * A periodic job that fails stops repeating.
     */
    public data class Failure(

        /**
         * Payload exposed through [JobInfo.outputData].
         */
        public val outputData: JobData = JobData.EMPTY
    ) : JobResult()

    /**
     * The job should run again later, after the backoff delay of its request.
     */
    public data object Retry : JobResult()

    public companion object {

        /**
         * Creates a [Success] result.
         */
        public fun success(outputData: JobData = JobData.EMPTY): JobResult = Success(outputData)

        /**
         * Creates a [Failure] result.
         */
        public fun failure(outputData: JobData = JobData.EMPTY): JobResult = Failure(outputData)

        /**
         * Returns the [Retry] result.
         */
        public fun retry(): JobResult = Retry
    }
}
