package tech.ryadom.jabbit

/**
 * Watches what happens to jobs, for logging, analytics and crash reporting.
 *
 * Every method has an empty body, so an implementation overrides only what it cares about. Install
 * one — or several — through `jabbit { listener(...) }`.
 *
 * Callbacks arrive on whichever thread the job changed on, and a listener that throws is logged and
 * skipped rather than allowed to break the scheduler. They are meant for quick, non-blocking work:
 * anything slower belongs in a coroutine the listener launches itself.
 *
 * ```
 * jabbit {
 *     worker(SyncJob) { SyncWorker(api) }
 *
 *     listener(object : JabbitListener {
 *         override fun onFailed(job: JobInfo, error: Throwable?) {
 *             crashReporter.report("job ${job.typeName} failed: ${job.failureReason}", error)
 *         }
 *     })
 * }
 * ```
 *
 * On Android the scheduler is `WorkManager`, which also cancels work on its own — after
 * `WorkManager.cancelAllWork()`, or when the application data is cleared. Those cancellations do
 * not reach [onCancelled], because they never pass through Jabbit.
 */
public interface JabbitListener {

    /**
     * The job was accepted into the queue.
     *
     * It arrives before the job is handed to the platform, so it always precedes [onStarted]. If
     * handing over then fails, the exception surfaces from the `enqueue` call and no further event
     * follows for that job.
     */
    public fun onEnqueued(job: JobInfo) {}

    /**
     * The job started running. Its constraints were satisfied and a slot was free.
     */
    public fun onStarted(job: JobInfo) {}

    /**
     * The job finished successfully. A periodic job is already waiting for its next period.
     */
    public fun onSucceeded(job: JobInfo) {}

    /**
     * The job gave up for good, either because the worker said so or because it ran out of
     * attempts. [JobInfo.failureReason] says which, and [error] is set when the worker threw.
     */
    public fun onFailed(job: JobInfo, error: Throwable?) {}

    /**
     * The worker asked to run again.
     *
     * [delayMillis] says how far away the next attempt is, and is `null` on Android, where
     * `WorkManager` owns the schedule and does not say when it will come back.
     */
    public fun onRetryScheduled(job: JobInfo, delayMillis: Long?) {}

    /**
     * The platform stopped the job mid-run — constraints stopped holding, a background window
     * expired, or the process went away. It returns to [JobState.ENQUEUED] and runs again later.
     */
    public fun onStopped(job: JobInfo) {}

    /**
     * The job will not run: it was cancelled, or replaced by another job of the same unique name.
     */
    public fun onCancelled(job: JobInfo) {}
}
