package tech.ryadom.jabbit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal const val RESERVED_TAG_PREFIX: String = "tech.ryadom.jabbit."

/**
 * A unit of background work handed to [Jabbit.enqueue].
 *
 * Build requests with [oneTimeJob] / [periodicJob], or with the nested builders when calling from
 * Swift or Java.
 */
public sealed class JobRequest {

    /**
     * Identifier the job will be enqueued under.
     */
    public abstract val id: JobId

    /**
     * Name of the worker registered in [JabbitConfiguration].
     */
    public abstract val workerName: String

    /**
     * Payload passed to the worker as [JobExecution.inputData].
     */
    public abstract val inputData: JobData

    /**
     * Device conditions required before the job may run.
     */
    public abstract val constraints: Constraints

    /**
     * Delay before the first run.
     */
    public abstract val initialDelay: Duration

    /**
     * How the retry delay grows between attempts.
     */
    public abstract val backoffPolicy: BackoffPolicy

    /**
     * Delay applied to the first retry.
     */
    public abstract val backoffDelay: Duration

    /**
     * Tags used to observe or cancel groups of jobs.
     */
    public abstract val tags: Set<String>

    /**
     * [initialDelay] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val initialDelayMillis: Long get() = initialDelay.inWholeMilliseconds

    /**
     * [backoffDelay] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val backoffDelayMillis: Long get() = backoffDelay.inWholeMilliseconds
}

/**
 * A job that runs once.
 *
 * ```
 * val request = oneTimeJob(SyncWorker.NAME) {
 *     setInputData(jobDataOf("full" to true))
 *     setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
 *     setInitialDelay(10.seconds)
 *     addTag("sync")
 * }
 * ```
 */
public class OneTimeJobRequest internal constructor(
    override val id: JobId,
    override val workerName: String,
    override val inputData: JobData,
    override val constraints: Constraints,
    override val initialDelay: Duration,
    override val backoffPolicy: BackoffPolicy,
    override val backoffDelay: Duration,
    override val tags: Set<String>
) : JobRequest() {

    /**
     * Builder for [OneTimeJobRequest].
     */
    public class Builder(private val workerName: String) {

        private var id: JobId = JobId.random()
        private var inputData: JobData = JobData.EMPTY
        private var constraints: Constraints = Constraints.NONE
        private var initialDelay: Duration = ZERO
        private var backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
        private var backoffDelay: Duration = BackoffPolicy.DEFAULT_DELAY
        private val tags: MutableSet<String> = mutableSetOf()

        /**
         * Uses an explicit identifier instead of a generated one.
         */
        public fun setId(id: JobId): Builder = apply { this.id = id }

        /**
         * Sets the payload passed to the worker.
         */
        public fun setInputData(inputData: JobData): Builder = apply { this.inputData = inputData }

        /**
         * Sets the device conditions required before the job may run.
         */
        public fun setConstraints(constraints: Constraints): Builder = apply {
            this.constraints = constraints
        }

        /**
         * Delays the first run by [duration].
         */
        public fun setInitialDelay(duration: Duration): Builder = apply { initialDelay = duration }

        /**
         * Sets how retries are spaced apart.
         *
         * [delay] is coerced into `[BackoffPolicy.MIN_DELAY, BackoffPolicy.MAX_DELAY]`.
         */
        public fun setBackoffCriteria(policy: BackoffPolicy, delay: Duration): Builder = apply {
            backoffPolicy = policy
            backoffDelay = delay
        }

        /**
         * Delays the first run by [millis].
         */
        public fun setInitialDelayMillis(millis: Long): Builder =
            setInitialDelay(millis.milliseconds)

        /**
         * Sets how retries are spaced apart, with the first retry delayed by [millis].
         */
        public fun setBackoffCriteriaMillis(policy: BackoffPolicy, millis: Long): Builder =
            setBackoffCriteria(policy, millis.milliseconds)

        /**
         * Adds a tag used to observe or cancel this job together with others.
         */
        public fun addTag(tag: String): Builder = apply { tags += tag }

        /**
         * Adds several tags at once.
         */
        public fun addTags(tags: Iterable<String>): Builder = apply { this.tags += tags }

        /**
         * Builds the request.
         */
        public fun build(): OneTimeJobRequest {
            validateCommon(workerName, initialDelay, tags)
            return OneTimeJobRequest(
                id = id,
                workerName = workerName,
                inputData = inputData,
                constraints = constraints,
                initialDelay = initialDelay,
                backoffPolicy = backoffPolicy,
                backoffDelay = backoffDelay.coerceIn(
                    BackoffPolicy.MIN_DELAY,
                    BackoffPolicy.MAX_DELAY
                ),
                tags = tags.toSet()
            )
        }
    }
}

/**
 * A job that repeats until it is cancelled or fails.
 *
 * The job runs at most once per [repeatInterval] and becomes eligible [flexInterval] before the
 * end of each interval. Neither platform guarantees an exact moment: Android batches periodic work
 * with other system wake-ups, and iOS runs it opportunistically through `BGTaskScheduler`.
 *
 * ```
 * val request = periodicJob(CleanupWorker.NAME, repeatInterval = 6.hours) {
 *     setConstraints(constraints { requiresCharging = true })
 * }
 * ```
 */
public class PeriodicJobRequest internal constructor(
    override val id: JobId,
    override val workerName: String,
    override val inputData: JobData,
    override val constraints: Constraints,
    override val initialDelay: Duration,
    override val backoffPolicy: BackoffPolicy,
    override val backoffDelay: Duration,
    override val tags: Set<String>,

    /**
     * Length of one period.
     */
    public val repeatInterval: Duration,

    /**
     * Window at the end of each period in which the job may run.
     */
    public val flexInterval: Duration
) : JobRequest() {

    /**
     * [repeatInterval] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val repeatIntervalMillis: Long get() = repeatInterval.inWholeMilliseconds

    /**
     * [flexInterval] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val flexIntervalMillis: Long get() = flexInterval.inWholeMilliseconds

    /**
     * Builder for [PeriodicJobRequest].
     */
    public class Builder(
        private val workerName: String,
        private val repeatInterval: Duration
    ) {

        private var id: JobId = JobId.random()
        private var inputData: JobData = JobData.EMPTY
        private var constraints: Constraints = Constraints.NONE
        private var initialDelay: Duration = ZERO
        private var backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
        private var backoffDelay: Duration = BackoffPolicy.DEFAULT_DELAY
        private var flexInterval: Duration? = null
        private val tags: MutableSet<String> = mutableSetOf()

        /**
         * Uses an explicit identifier instead of a generated one.
         */
        public fun setId(id: JobId): Builder = apply { this.id = id }

        /**
         *  Sets the payload passed to the worker.
         **/
        public fun setInputData(inputData: JobData): Builder = apply { this.inputData = inputData }

        /**
         * Sets the device conditions required before the job may run.
         */
        public fun setConstraints(constraints: Constraints): Builder = apply {
            this.constraints = constraints
        }

        /**
         * Delays the first period by [duration].
         */
        public fun setInitialDelay(duration: Duration): Builder = apply { initialDelay = duration }

        /**
         * Sets how retries are spaced apart.
         *
         * [delay] is coerced into `[BackoffPolicy.MIN_DELAY, BackoffPolicy.MAX_DELAY]`.
         */
        public fun setBackoffCriteria(policy: BackoffPolicy, delay: Duration): Builder = apply {
            backoffPolicy = policy
            backoffDelay = delay
        }

        /**
         * Restricts the job to the last [duration] of every period.
         *
         * The value is coerced into `[MIN_FLEX_INTERVAL, repeatInterval]`. Without it the job may
         * run at any point of the period.
         */
        public fun setFlexInterval(duration: Duration): Builder = apply { flexInterval = duration }

        /**
         * Delays the first period by [millis].
         */
        public fun setInitialDelayMillis(millis: Long): Builder =
            setInitialDelay(millis.milliseconds)

        /**
         * Sets how retries are spaced apart, with the first retry delayed by [millis].
         */
        public fun setBackoffCriteriaMillis(policy: BackoffPolicy, millis: Long): Builder =
            setBackoffCriteria(policy, millis.milliseconds)

        /**
         * Restricts the job to the last [millis] milliseconds of every period.
         */
        public fun setFlexIntervalMillis(millis: Long): Builder =
            setFlexInterval(millis.milliseconds)

        /**
         * Adds a tag used to observe or cancel this job together with others.
         */
        public fun addTag(tag: String): Builder = apply { tags += tag }

        /**
         * Adds several tags at once.
         */
        public fun addTags(tags: Iterable<String>): Builder = apply { this.tags += tags }

        /**
         * Builds the request.
         */
        public fun build(): PeriodicJobRequest {
            validateCommon(workerName, initialDelay, tags)
            val interval = repeatInterval.coerceAtLeast(MIN_PERIODIC_INTERVAL)
            val flex = (flexInterval ?: interval).coerceIn(MIN_FLEX_INTERVAL, interval)
            return PeriodicJobRequest(
                id = id,
                workerName = workerName,
                inputData = inputData,
                constraints = constraints,
                initialDelay = initialDelay,
                backoffPolicy = backoffPolicy,
                backoffDelay = backoffDelay.coerceIn(
                    BackoffPolicy.MIN_DELAY,
                    BackoffPolicy.MAX_DELAY
                ),
                tags = tags.toSet(),
                repeatInterval = interval,
                flexInterval = flex
            )
        }
    }

    public companion object {

        /**
         * Shortest period both platforms accept. Shorter values are coerced up to it.
         */
        public val MIN_PERIODIC_INTERVAL: Duration = 15.minutes

        /**
         * Shortest flex window both platforms accept. Shorter values are coerced up to it.
         */
        public val MIN_FLEX_INTERVAL: Duration = 5.minutes
    }
}

/**
 * Builds a [OneTimeJobRequest] for the worker registered as [workerName].
 */
public fun oneTimeJob(
    workerName: String,
    configure: OneTimeJobRequest.Builder.() -> Unit = {}
): OneTimeJobRequest = OneTimeJobRequest.Builder(workerName).apply(configure).build()

/**
 * Builds a [PeriodicJobRequest] for the worker registered as [workerName], repeating every
 * [repeatInterval].
 */
public fun periodicJob(
    workerName: String,
    repeatInterval: Duration,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = PeriodicJobRequest.Builder(workerName, repeatInterval)
    .apply(configure)
    .build()

/**
 * Builds a [PeriodicJobRequest] repeating every [repeatIntervalMillis] milliseconds, for callers
 * without `kotlin.time.Duration`.
 */
public fun periodicJobMillis(
    workerName: String,
    repeatIntervalMillis: Long,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = periodicJob(workerName, repeatIntervalMillis.milliseconds, configure)

private fun validateCommon(workerName: String, initialDelay: Duration, tags: Set<String>) {
    require(workerName.isNotBlank()) { "Worker name must not be blank" }
    require(!initialDelay.isNegative()) { "Initial delay must not be negative, was $initialDelay" }
    tags.forEach { tag ->
        require(tag.isNotBlank()) { "Tags must not be blank" }
        require(!tag.startsWith(RESERVED_TAG_PREFIX)) {
            "Tags starting with '$RESERVED_TAG_PREFIX' are reserved by Jabbit, got '$tag'"
        }
    }
}
