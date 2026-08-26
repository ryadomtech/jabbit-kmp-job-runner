package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.encodePayload
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal const val RESERVED_TAG_PREFIX: String = "tech.ryadom.jabbit."

/**
 * A unit of background work handed to [Jabbit.enqueue].
 *
 * Build requests with [oneTimeJob] and [periodicJob], which take the [JobType] of the job and the
 * payload it should run with.
 */
public sealed class JobRequest {

    /**
     * Identifier the job will be enqueued under.
     */
    public abstract val id: JobId

    /**
     * Name of the [JobType] this job belongs to.
     */
    public abstract val typeName: String

    internal abstract val encodedInput: String

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
     * How many times the job may be started before it is given up on.
     *
     * The count is [JobExecution.runAttemptCount] plus one, so it includes attempts that were cut
     * short by the system as well as the ones the worker itself ended with [JobResult.Retry]. Once
     * it is reached the job finishes as [JobState.FAILED] instead of being retried again.
     */
    public abstract val maxAttempts: Int

    /**
     * [initialDelay] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val initialDelayMillis: Long get() = initialDelay.inWholeMilliseconds

    /**
     * [backoffDelay] in milliseconds, for callers without `kotlin.time.Duration`.
     */
    public val backoffDelayMillis: Long get() = backoffDelay.inWholeMilliseconds

    /**
     * Settings shared by every kind of request.
     */
    public sealed class Builder<B : Builder<B>> {

        internal var id: JobId = JobId.random()
        internal var constraints: Constraints = Constraints.NONE
        internal var initialDelay: Duration = ZERO
        internal var backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL
        internal var backoffDelay: Duration = BackoffPolicy.DEFAULT_DELAY
        internal var maxAttempts: Int = UNLIMITED_ATTEMPTS
        internal val tags: MutableSet<String> = mutableSetOf()

        @Suppress("UNCHECKED_CAST")
        private val self: B get() = this as B

        /**
         * Uses an explicit identifier instead of a generated one.
         */
        public fun setId(id: JobId): B {
            this.id = id
            return self
        }

        /**
         * Sets the device conditions required before the job may run.
         */
        public fun setConstraints(constraints: Constraints): B {
            this.constraints = constraints
            return self
        }

        /**
         * Delays the first run by [duration].
         */
        public fun setInitialDelay(duration: Duration): B {
            initialDelay = duration
            return self
        }

        /**
         * Delays the first run by [millis].
         */
        public fun setInitialDelayMillis(millis: Long): B = setInitialDelay(millis.milliseconds)

        /**
         * Sets how retries are spaced apart.
         *
         * [delay] is coerced into `[BackoffPolicy.MIN_DELAY, BackoffPolicy.MAX_DELAY]`.
         */
        public fun setBackoffCriteria(policy: BackoffPolicy, delay: Duration): B {
            backoffPolicy = policy
            backoffDelay = delay
            return self
        }

        /**
         * Sets how retries are spaced apart, with the first retry delayed by [millis].
         */
        public fun setBackoffCriteriaMillis(policy: BackoffPolicy, millis: Long): B =
            setBackoffCriteria(policy, millis.milliseconds)

        /**
         * Gives up on the job once it has been started [count] times.
         *
         * @throws IllegalArgumentException when [count] is not positive.
         */
        public fun setMaxAttempts(count: Int): B {
            require(count > 0) { "maxAttempts must be positive, was $count" }
            maxAttempts = count
            return self
        }

        /**
         * Adds a tag used to observe or cancel this job together with others.
         */
        public fun addTag(tag: String): B {
            tags += tag
            return self
        }

        /**
         * Adds several tags at once.
         */
        public fun addTags(tags: Iterable<String>): B {
            this.tags += tags
            return self
        }

        internal fun validate() {
            require(!initialDelay.isNegative()) {
                "Initial delay must not be negative, was $initialDelay"
            }
            tags.forEach { tag ->
                require(tag.isNotBlank()) { "Tags must not be blank" }
                require(!tag.startsWith(RESERVED_TAG_PREFIX)) {
                    "Tags starting with '$RESERVED_TAG_PREFIX' are reserved by Jabbit, got '$tag'"
                }
            }
        }

        internal fun coercedBackoffDelay(): Duration =
            backoffDelay.coerceIn(BackoffPolicy.MIN_DELAY, BackoffPolicy.MAX_DELAY)
    }

    public companion object {

        /**
         * Value of [maxAttempts] that lets a job retry without a limit.
         */
        public const val UNLIMITED_ATTEMPTS: Int = Int.MAX_VALUE
    }
}

/**
 * A job that runs once.
 *
 * ```
 * val request = oneTimeJob(SyncJob, SyncInput(since = lastSync)) {
 *     setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
 *     setInitialDelay(10.seconds)
 *     addTag("sync")
 * }
 * ```
 */
public class OneTimeJobRequest internal constructor(
    override val id: JobId,
    override val typeName: String,
    override val encodedInput: String,
    override val constraints: Constraints,
    override val initialDelay: Duration,
    override val backoffPolicy: BackoffPolicy,
    override val backoffDelay: Duration,
    override val tags: Set<String>,
    override val maxAttempts: Int
) : JobRequest() {

    /**
     * Builder for [OneTimeJobRequest].
     */
    public class Builder internal constructor(
        private val typeName: String,
        private val encodedInput: String
    ) : JobRequest.Builder<Builder>() {

        /**
         * Builds the request.
         */
        public fun build(): OneTimeJobRequest {
            validate()
            return OneTimeJobRequest(
                id = id,
                typeName = typeName,
                encodedInput = encodedInput,
                constraints = constraints,
                initialDelay = initialDelay,
                backoffPolicy = backoffPolicy,
                backoffDelay = coercedBackoffDelay(),
                tags = tags.toSet(),
                maxAttempts = maxAttempts
            )
        }
    }
}

/**
 * A job that repeats until it is cancelled or fails.
 *
 * The job runs at most once per [repeatInterval] and becomes eligible [flexInterval] before the end
 * of each interval. Neither platform guarantees an exact moment: Android batches periodic work with
 * other system wake-ups, and iOS runs it opportunistically through `BGTaskScheduler`.
 */
public class PeriodicJobRequest internal constructor(
    override val id: JobId,
    override val typeName: String,
    override val encodedInput: String,
    override val constraints: Constraints,
    override val initialDelay: Duration,
    override val backoffPolicy: BackoffPolicy,
    override val backoffDelay: Duration,
    override val tags: Set<String>,
    override val maxAttempts: Int,

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
    public class Builder internal constructor(
        private val typeName: String,
        private val encodedInput: String,
        private val repeatInterval: Duration
    ) : JobRequest.Builder<Builder>() {

        private var flexInterval: Duration? = null

        /**
         * Restricts the job to the last [duration] of every period.
         *
         * The value is coerced into `[MIN_FLEX_INTERVAL, repeatInterval]`. Without it the job may
         * run at any point of the period.
         */
        public fun setFlexInterval(duration: Duration): Builder {
            flexInterval = duration
            return this
        }

        /**
         * Restricts the job to the last [millis] milliseconds of every period.
         */
        public fun setFlexIntervalMillis(millis: Long): Builder =
            setFlexInterval(millis.milliseconds)

        /**
         * Builds the request.
         */
        public fun build(): PeriodicJobRequest {
            validate()
            val interval = repeatInterval.coerceAtLeast(MIN_PERIODIC_INTERVAL)
            val flex = (flexInterval ?: interval).coerceIn(MIN_FLEX_INTERVAL, interval)

            return PeriodicJobRequest(
                id = id,
                typeName = typeName,
                encodedInput = encodedInput,
                constraints = constraints,
                initialDelay = initialDelay,
                backoffPolicy = backoffPolicy,
                backoffDelay = coercedBackoffDelay(),
                tags = tags.toSet(),
                maxAttempts = maxAttempts,
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
 * Builds a [OneTimeJobRequest] running [type] with [input].
 */
public fun <I, O> oneTimeJob(
    type: JobType<I, O>,
    input: I,
    configure: OneTimeJobRequest.Builder.() -> Unit = {}
): OneTimeJobRequest = OneTimeJobRequest.Builder(
    typeName = type.name,
    encodedInput = encodePayload(type.inputSerializer, input)
).apply(configure).build()

/**
 * Builds a [OneTimeJobRequest] for a job that needs no input.
 */
public fun <O> oneTimeJob(
    type: JobType<Unit, O>,
    configure: OneTimeJobRequest.Builder.() -> Unit = {}
): OneTimeJobRequest = oneTimeJob(type, Unit, configure)

/**
 * Builds a [PeriodicJobRequest] running [type] with [input] every [repeatInterval].
 */
public fun <I, O> periodicJob(
    type: JobType<I, O>,
    input: I,
    repeatInterval: Duration,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = PeriodicJobRequest.Builder(
    typeName = type.name,
    encodedInput = encodePayload(type.inputSerializer, input),
    repeatInterval = repeatInterval
).apply(configure).build()

/**
 * Builds a [PeriodicJobRequest] for a job that needs no input.
 */
public fun <O> periodicJob(
    type: JobType<Unit, O>,
    repeatInterval: Duration,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = periodicJob(type, Unit, repeatInterval, configure)

/**
 * Builds a [PeriodicJobRequest] repeating every [repeatIntervalMillis] milliseconds, for callers
 * without `kotlin.time.Duration`.
 */
public fun <I, O> periodicJobMillis(
    type: JobType<I, O>,
    input: I,
    repeatIntervalMillis: Long,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = periodicJob(type, input, repeatIntervalMillis.milliseconds, configure)

/**
 * Builds a [PeriodicJobRequest] for a job that needs no input, repeating every
 * [repeatIntervalMillis] milliseconds.
 */
public fun <O> periodicJobMillis(
    type: JobType<Unit, O>,
    repeatIntervalMillis: Long,
    configure: PeriodicJobRequest.Builder.() -> Unit = {}
): PeriodicJobRequest = periodicJob(type, Unit, repeatIntervalMillis.milliseconds, configure)
