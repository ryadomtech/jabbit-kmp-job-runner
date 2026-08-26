package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.decodePayload

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
     * Cancelled before it could finish.
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
 * Finished jobs are kept for a while so their outcome can still be read, then pruned.
 */
@ConsistentCopyVisibility
public data class JobInfo internal constructor(

    /**
     * Identifier of the job.
     */
    public val id: JobId,

    /**
     * Name of the [JobType] the job belongs to.
     */
    public val typeName: String,

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
     * What the last [JobExecution.setProgress] call reported, or `null` when the job never
     * reported anything or has already finished.
     */
    public val progress: JobProgress?,

    /**
     * Why the job failed, when it failed and the worker said why.
     */
    public val failureReason: String?,

    /**
     * Number of times the job has been started, starting at zero for the first attempt.
     */
    public val runAttemptCount: Int,

    /**
     * Epoch milliseconds of the next planned run, or `null` when no run is planned.
     */
    public val nextScheduleTimeMillis: Long?,

    internal val encodedOutput: String?
)

/**
 * Reads what the job produced, or `null` when it has not succeeded yet.
 *
 * [type] has to be the one the job was enqueued with; a payload written by a different type reads
 * back as `null` rather than throwing.
 *
 * ```
 * val items = jabbit.getJobInfo(id)?.output(SyncJob)?.items
 * ```
 */
public fun <O> JobInfo.output(type: JobType<*, O>): O? =
    decodePayload(type.outputSerializer, encodedOutput)
