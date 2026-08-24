package tech.ryadom.jabbit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/**
 * Everything the scheduler needs to run jobs: how to build workers, where to log, and the limits
 * it applies to itself.
 *
 * ```
 * val configuration = jabbitConfiguration {
 *     worker(SyncWorker.NAME) { SyncWorker(api) }
 *     worker(CleanupWorker.NAME) { CleanupWorker(database) }
 *     logger(JabbitLogger.Console)
 * }
 * ```
 */
public class JabbitConfiguration internal constructor(

    /** Factory used to create a worker when a job starts. */
    public val workerFactory: JabbitWorkerFactory,

    /**
     * Worker names registered through [Builder.worker].
     *
     * Enqueueing an unknown name fails fast unless a custom [JabbitWorkerFactory] is installed.
     */
    public val registeredWorkerNames: Set<String>,

    /** Sink for diagnostic output. */
    public val logger: JabbitLogger,

    /**
     * Number of jobs allowed to run at the same time.
     *
     * Applies to iOS only; on Android concurrency is governed by the executor of `WorkManager`.
     */
    public val maxConcurrentJobs: Int,

    /**
     * How long finished jobs stay observable through [Jabbit.getJobInfo] before they are pruned.
     *
     * Applies to iOS only; on Android the retention of `WorkManager` applies.
     */
    public val finishedJobRetention: Duration,

    internal val hasCustomWorkerFactory: Boolean
) {

    /** Builder for [JabbitConfiguration]. */
    public class Builder {

        private val workers = mutableMapOf<String, () -> JabbitWorker>()
        private var fallbackFactory: JabbitWorkerFactory? = null
        private var logger: JabbitLogger = JabbitLogger.None
        private var maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS
        private var finishedJobRetention: Duration = DEFAULT_FINISHED_JOB_RETENTION

        /**
         * Registers [factory] under [name].
         *
         * The name is what [JobRequest.workerName] refers to, and it is persisted with the job, so
         * it must stay stable across app releases.
         */
        public fun worker(name: String, factory: () -> JabbitWorker): Builder = apply {
            require(name.isNotBlank()) { "Worker name must not be blank" }
            require(workers.put(name, factory) == null) {
                "Worker '$name' is already registered"
            }
        }

        /**
         * Installs a factory consulted for names that were not registered through [worker].
         *
         * Use it to resolve workers through a dependency injection container.
         */
        public fun workerFactory(factory: JabbitWorkerFactory): Builder = apply {
            fallbackFactory = factory
        }

        /** Routes diagnostic output into [logger]. */
        public fun logger(logger: JabbitLogger): Builder = apply { this.logger = logger }

        /** Limits how many jobs may run at the same time on iOS. */
        public fun maxConcurrentJobs(count: Int): Builder = apply {
            require(count > 0) { "maxConcurrentJobs must be positive, was $count" }
            maxConcurrentJobs = count
        }

        /** Sets how long finished jobs stay observable on iOS. */
        public fun finishedJobRetention(duration: Duration): Builder = apply {
            require(duration.isPositive()) {
                "finishedJobRetention must be positive, was $duration"
            }
            finishedJobRetention = duration
        }

        /** Sets how long finished jobs stay observable on iOS, in milliseconds. */
        public fun finishedJobRetentionMillis(millis: Long): Builder =
            finishedJobRetention(millis.milliseconds)

        /** Builds the configuration. */
        public fun build(): JabbitConfiguration {
            val registered = workers.toMap()
            val fallback = fallbackFactory
            check(registered.isNotEmpty() || fallback != null) {
                "No workers registered: call worker(name) { ... } or workerFactory(...)"
            }
            return JabbitConfiguration(
                workerFactory = { name ->
                    registered[name]?.invoke() ?: fallback?.createWorker(name)
                },
                registeredWorkerNames = registered.keys,
                logger = logger,
                maxConcurrentJobs = maxConcurrentJobs,
                finishedJobRetention = finishedJobRetention,
                hasCustomWorkerFactory = fallback != null
            )
        }
    }

    public companion object {

        internal const val DEFAULT_MAX_CONCURRENT_JOBS: Int = 4

        internal val DEFAULT_FINISHED_JOB_RETENTION: Duration = 7.days
    }
}

/**
 * Builds a [JabbitConfiguration] with a DSL.
 *
 * ```
 * val configuration = jabbitConfiguration {
 *     worker("sync") { SyncWorker(api) }
 * }
 * ```
 */
public fun jabbitConfiguration(block: JabbitConfiguration.Builder.() -> Unit): JabbitConfiguration =
    JabbitConfiguration.Builder().apply(block).build()

internal fun JabbitConfiguration.requireKnownWorker(workerName: String) {
    if (hasCustomWorkerFactory) return
    require(workerName in registeredWorkerNames) {
        "Worker '$workerName' is not registered. Known workers: " +
                registeredWorkerNames.sorted().joinToString()
    }
}
