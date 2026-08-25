package tech.ryadom.jabbit

/**
 * Lifecycle state of an enqueued job.
 */
public enum class JobState {

    /**
     * Waiting for its schedule and constraints.
     */
    ENQUEUED,

    /**
     * Currently executing.
     */
    RUNNING,

    /**
     * Blocked by work it depends on. Reported by Android only.
     */
    BLOCKED,

    /**
     * Finished with [JobResult.Success].
     */
    SUCCEEDED,

    /**
     * Finished with [JobResult.Failure], or the worker could not be created.
     */
    FAILED,

    /**
     * Canceled before it could finish.
     */
    CANCELLED;

    /**
     * Returns `true` when the job reached a terminal state.
     */
    public val isFinished: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

/**
 * Snapshot of an enqueued job.
 *
 * Finished jobs are kept for [JabbitConfiguration.finishedJobRetention] so their output can still
 * be read, then pruned.
 */
public data class JobInfo(

    /**
     * Identifier of the job.
     */
    public val id: JobId,

    /**
     * Name the job's worker is registered under.
     */
    public val workerName: String,

    /**
     * Current state.
     */
    public val state: JobState,

    /**
     * Tags attached to the request.
     */
    public val tags: Set<String>,

    /**
     * Unique name the job was enqueued under, or `null` for jobs enqueued without one.
     */
    public val uniqueName: String?,

    /**
     * Payload of the last [JobResult.Success] or [JobResult.Failure].
     */
    public val outputData: JobData,

    /**
     * Payload of the last [JobExecution.setProgress] call.
     */
    public val progress: JobData,

    /**
     * Number of times the job has been started, starting at zero for the first attempt.
     */
    public val runAttemptCount: Int,

    /**
     * Epoch milliseconds of the next planned run, or `null` when no run is planned.
     */
    public val nextScheduleTimeMillis: Long?
)
