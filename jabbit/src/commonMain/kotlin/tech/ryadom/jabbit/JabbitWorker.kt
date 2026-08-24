package tech.ryadom.jabbit

/**
 * A unit of background work.
 *
 * Workers are registered by name in [JabbitConfiguration] and instantiated by the library every
 * time a job starts, so they must be cheap to create and must not hold state between runs.
 *
 * [doWork] runs on a background dispatcher and is cancelled cooperatively when the platform stops
 * the job — when constraints stop being satisfied, when the job is cancelled, or when the iOS
 * background window expires. Long-running loops should therefore check `isActive` or call
 * suspending functions that honour cancellation. A cancelled job returns to
 * [JobState.ENQUEUED] and runs again later.
 *
 * ```
 * class SyncWorker(private val api: Api) : JabbitWorker {
 *
 *     override suspend fun doWork(job: JobExecution): JobResult {
 *         val since = job.inputData.getLong("since") ?: 0L
 *         return try {
 *             api.sync(since)
 *             JobResult.success(jobDataOf("syncedAt" to now()))
 *         } catch (e: IOException) {
 *             if (job.runAttemptCount < 5) JobResult.retry() else JobResult.failure()
 *         }
 *     }
 *
 *     companion object {
 *         const val NAME = "sync"
 *     }
 * }
 * ```
 */
public fun interface JabbitWorker {

    /**
     * Performs the work and reports its outcome.
     *
     * An exception thrown out of this method is caught and reported as [JobResult.Failure].
     */
    public suspend fun doWork(job: JobExecution): JobResult
}

/**
 * Everything a running job knows about itself.
 */
public class JobExecution internal constructor(

    /** Identifier of the running job. */
    public val id: JobId,

    /** Name the worker is registered under. */
    public val workerName: String,

    /** Payload supplied by [JobRequest.inputData]. */
    public val inputData: JobData,

    /** Tags attached to the request. */
    public val tags: Set<String>,

    /**
     * Number of previous attempts, starting at zero.
     *
     * Increases on every [JobResult.Retry] and every interruption, which makes it the value to
     * check when giving up: `if (job.runAttemptCount >= 5) JobResult.failure() else JobResult.retry()`.
     */
    public val runAttemptCount: Int,

    private val progressReporter: ProgressReporter
) {

    /**
     * Publishes intermediate progress, observable through [JobInfo.progress].
     *
     * Progress is dropped once the job finishes.
     */
    public suspend fun setProgress(data: JobData) {
        progressReporter.report(data)
    }

    public companion object {

        /** Creates an instance for testing a worker without a scheduler. */
        public fun forTesting(
            id: JobId = JobId.random(),
            workerName: String = "test",
            inputData: JobData = JobData.EMPTY,
            tags: Set<String> = emptySet(),
            runAttemptCount: Int = 0,
            progressReporter: ProgressReporter = ProgressReporter { }
        ): JobExecution = JobExecution(
            id = id,
            workerName = workerName,
            inputData = inputData,
            tags = tags,
            runAttemptCount = runAttemptCount,
            progressReporter = progressReporter
        )
    }
}

/** Receiver of [JobExecution.setProgress] calls. */
public fun interface ProgressReporter {

    /** Publishes [data] as the current progress of the running job. */
    public suspend fun report(data: JobData)
}

/**
 * Creates workers by the name they were registered under.
 *
 * Implement this to resolve workers through a dependency injection container instead of
 * registering them one by one with [JabbitConfiguration.Builder.worker].
 */
public fun interface JabbitWorkerFactory {

    /**
     * Returns a new worker for [workerName], or `null` when this factory does not know the name.
     *
     * A job whose worker cannot be created finishes as [JobState.FAILED].
     */
    public fun createWorker(workerName: String): JabbitWorker?
}
