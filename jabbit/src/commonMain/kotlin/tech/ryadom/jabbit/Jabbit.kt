package tech.ryadom.jabbit

import kotlinx.coroutines.flow.Flow

/**
 * Schedules background jobs and reports on them.
 *
 * Jobs survive process death and reboots: on Android they are handed to `WorkManager`, on iOS they
 * are persisted by Jabbit itself and executed while the app runs or inside the background windows
 * granted by `BGTaskScheduler`.
 *
 * Create an instance once per process — on Android from `Application.onCreate`, on iOS from
 * `application(_:didFinishLaunchingWithOptions:)` — with the platform `createJabbit` function, and
 * share it through dependency injection.
 *
 * ```
 * val jabbit = createJabbit(
 *     context = applicationContext,
 *     configuration = jabbitConfiguration {
 *         worker(SyncWorker.NAME) { SyncWorker(api) }
 *     }
 * )
 *
 * jabbit.enqueueUniquePeriodic(
 *     uniqueName = "sync",
 *     policy = ExistingPeriodicJobPolicy.KEEP,
 *     request = periodicJob(SyncWorker.NAME, repeatInterval = 1.hours) {
 *         setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
 *     }
 * )
 * ```
 *
 * Every method is safe to call from any thread.
 */
public interface Jabbit {

    /**
     * Enqueues [request].
     *
     * @throws IllegalArgumentException when the request names a worker that is not registered.
     */
    public suspend fun enqueue(request: JobRequest)

    /**
     * Enqueues [requests] as one batch.
     */
    public suspend fun enqueue(requests: List<JobRequest>)

    /**
     * Enqueues [request] as the only job carrying [uniqueName], resolving a collision with an
     * unfinished job of the same name through [policy].
     *
     * This is how work that must not pile up is scheduled, such as a sync triggered from several
     * screens at once.
     */
    public suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    )

    /**
     * Enqueues [request] as the only periodic job carrying [uniqueName], resolving a collision with
     * an unfinished job of the same name through [policy].
     *
     * Calling this with [ExistingPeriodicJobPolicy.KEEP] on every app start is the usual way to
     * make sure a periodic job exists exactly once.
     */
    public suspend fun enqueueUniquePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        request: PeriodicJobRequest
    )

    /**
     * Cancels the job with [id].
     *
     * A running job is canceled cooperatively and does not run again. Finished jobs are unaffected.
     */
    public suspend fun cancelJob(id: JobId)

    /**
     * Cancels every unfinished job carrying [tag].
     */
    public suspend fun cancelJobsByTag(tag: String)

    /**
     * Cancels the unfinished job enqueued under [uniqueName].
     */
    public suspend fun cancelUniqueJob(uniqueName: String)

    /**
     * Cancels every unfinished job known to this instance.
     */
    public suspend fun cancelAllJobs()

    /**
     * Drops the records of finished jobs, releasing the storage they occupy.
     */
    public suspend fun pruneFinishedJobs()

    /**
     * Returns the current state of the job with [id], or `null` when it is unknown or pruned.
     */
    public suspend fun getJobInfo(id: JobId): JobInfo?

    /**
     * Returns the current state of every known job carrying [tag].
     */
    public suspend fun getJobInfosByTag(tag: String): List<JobInfo>

    /**
     * Returns the current state of every known job enqueued under [uniqueName].
     */
    public suspend fun getJobInfosForUniqueJob(uniqueName: String): List<JobInfo>

    /**
     * Emits the state of the job with [id] and every later change to it, or `null` while the job is
     * unknown.
     */
    public fun getJobInfoFlow(id: JobId): Flow<JobInfo?>

    /**
     * Emits the state of every known job carrying [tag] and every later change to it.
     */
    public fun getJobInfosByTagFlow(tag: String): Flow<List<JobInfo>>

    /**
     * Emits the state of every known job enqueued under [uniqueName] and every later change.
     */
    public fun getJobInfosForUniqueJobFlow(uniqueName: String): Flow<List<JobInfo>>
}
