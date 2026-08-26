package tech.ryadom.jabbit

/**
 * A unit of background work, typed by the payload it takes and the payload it returns.
 *
 * Workers are registered against a [JobType] in the `jabbit { }` builder and instantiated by the
 * library every time a job starts, so they must be cheap to create and must not hold state between
 * runs.
 *
 * [doWork] runs on a background dispatcher and is cancelled cooperatively when the platform stops
 * the job — when constraints stop being satisfied, when the job is cancelled, or when the iOS
 * background window expires. Long-running loops should therefore check `isActive` or call
 * suspending functions that honour cancellation. A cancelled job returns to [JobState.ENQUEUED]
 * and runs again later.
 *
 * ```
 * class SyncWorker(private val api: Api) : JabbitWorker<SyncInput, SyncOutput> {
 *
 *     override suspend fun doWork(job: JobExecution<SyncInput>): JobResult<SyncOutput> {
 *         return try {
 *             JobResult.success(SyncOutput(items = api.sync(job.input.since)))
 *         } catch (e: IOException) {
 *             JobResult.retry()
 *         }
 *     }
 * }
 * ```
 */
public fun interface JabbitWorker<I, O> {

    /**
     * Performs the work and reports its outcome.
     *
     * An exception thrown out of this method is caught and reported as [JobResult.Failure].
     */
    public suspend fun doWork(job: JobExecution<I>): JobResult<O>
}

/**
 * Everything a running job knows about itself.
 */
public class JobExecution<I> internal constructor(

    /**
     * Identifier of the running job.
     */
    public val id: JobId,

    /**
     * Name of the [JobType] this job was enqueued under.
     */
    public val typeName: String,

    /**
     * Payload the job was enqueued with.
     */
    public val input: I,

    /**
     * Tags attached to the request.
     */
    public val tags: Set<String>,

    /**
     * Number of previous attempts, starting at zero.
     *
     * Increases on every [JobResult.Retry] and every interruption, and is what
     * [JobRequest.maxAttempts] is measured against.
     */
    public val runAttemptCount: Int,

    private val progressReporter: ProgressReporter
) {

    /**
     * Publishes how far along the job is, observable through [JobInfo.progress].
     */
    public suspend fun setProgress(progress: JobProgress) {
        progressReporter.report(progress)
    }

    public companion object {

        /**
         * Creates an instance for testing a worker without a scheduler.
         */
        public fun <I> forTesting(
            input: I,
            id: JobId = JobId.random(),
            typeName: String = "test",
            tags: Set<String> = emptySet(),
            runAttemptCount: Int = 0,
            progressReporter: ProgressReporter = ProgressReporter { }
        ): JobExecution<I> = JobExecution(
            id = id,
            typeName = typeName,
            input = input,
            tags = tags,
            runAttemptCount = runAttemptCount,
            progressReporter = progressReporter
        )
    }
}

/**
 * Receiver of [JobExecution.setProgress] calls.
 */
public fun interface ProgressReporter {

    /**
     * Publishes [progress] as the current progress of the running job.
     */
    public suspend fun report(progress: JobProgress)
}
