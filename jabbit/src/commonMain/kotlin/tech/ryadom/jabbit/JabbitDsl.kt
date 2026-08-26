package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JabbitDefaults
import tech.ryadom.jabbit.internal.WorkerRegistration
import tech.ryadom.jabbit.internal.createPlatformJabbit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Marks the receivers of the `jabbit { }` builder so that nested blocks cannot see each other.
 */
@DslMarker
public annotation class JabbitDsl

/**
 * Creates a [Jabbit] for whichever platform the code is running on, from a single description that
 * lives in common code.
 *
 * ```
 * val jabbit = jabbit {
 *     worker(SyncJob) { SyncWorker(api) }
 *     logger(JabbitLogger.Console)
 *
 *     android { }
 *
 *     ios {
 *         backgroundTaskIdentifier = "com.example.app.jabbit"
 *     }
 *
 *     desktop {
 *         applicationName = "Example"
 *     }
 *
 *     browser {
 *         queueName = "example"
 *     }
 * }
 * ```
 *
 * Every platform block is optional and only the one matching the current platform is read, so the
 * same call compiles and runs everywhere. Nothing is passed in from platform code: on Android the
 * application context is picked up automatically before any application code runs.
 *
 * The platform `createJabbit` functions stay available for the cases this cannot cover — handing
 * Android a specific `Context`, or getting back the `AutoCloseable` desktop instance.
 *
 * @throws IllegalStateException when the current platform needs a setting the description does not
 * provide, such as `desktop { applicationName }`.
 */
public fun jabbit(configure: JabbitScope.() -> Unit): Jabbit =
    createPlatformJabbit(JabbitScope().apply(configure))

/**
 * Receiver of the `jabbit { }` builder.
 */
@JabbitDsl
public class JabbitScope internal constructor() {

    private val registrations = mutableMapOf<String, WorkerRegistration<*, *>>()
    private val listeners = mutableListOf<JabbitListener>()
    private var logger: JabbitLogger = JabbitLogger.None
    private var maxConcurrentJobs: Int = DEFAULT_MAX_CONCURRENT_JOBS
    private var finishedJobRetention: Duration = DEFAULT_FINISHED_JOB_RETENTION

    internal val androidOptions: AndroidScope = AndroidScope()
    internal val iosOptions: IosScope = IosScope()
    internal val desktopOptions: DesktopScope = DesktopScope()
    internal val browserOptions: BrowserScope = BrowserScope()

    /**
     * Registers the worker that runs jobs of [type].
     *
     * [factory] is called anew for every run, so it is the place to pull dependencies out of a
     * container rather than to cache anything.
     */
    public fun <I, O> worker(type: JobType<I, O>, factory: () -> JabbitWorker<I, O>) {
        require(type.name !in registrations) { "Job type '${type.name}' is already registered" }
        registrations[type.name] = WorkerRegistration(type, factory)
    }

    /**
     * Adds a listener watching what happens to jobs. Several may be installed.
     */
    public fun listener(listener: JabbitListener) {
        listeners += listener
    }

    /**
     * Routes diagnostic output into [logger].
     */
    public fun logger(logger: JabbitLogger) {
        this.logger = logger
    }

    /**
     * Limits how many jobs may run at the same time. Ignored on Android, where `WorkManager`
     * decides.
     */
    public fun maxConcurrentJobs(count: Int) {
        require(count > 0) { "maxConcurrentJobs must be positive, was $count" }
        maxConcurrentJobs = count
    }

    /**
     * Sets how long finished jobs stay observable. Ignored on Android, where the retention of
     * `WorkManager` applies.
     */
    public fun finishedJobRetention(duration: Duration) {
        require(duration.isPositive()) { "finishedJobRetention must be positive, was $duration" }
        finishedJobRetention = duration
    }

    /**
     * Settings that only apply when running on Android.
     */
    public fun android(configure: AndroidScope.() -> Unit) {
        androidOptions.configure()
    }

    /**
     * Settings that only apply when running on iOS.
     */
    public fun ios(configure: IosScope.() -> Unit) {
        iosOptions.configure()
    }

    /**
     * Settings that only apply when running on the desktop.
     */
    public fun desktop(configure: DesktopScope.() -> Unit) {
        desktopOptions.configure()
    }

    /**
     * Settings that only apply when running in a browser.
     */
    public fun browser(configure: BrowserScope.() -> Unit) {
        browserOptions.configure()
    }

    internal fun buildConfiguration(): JabbitConfiguration {
        check(registrations.isNotEmpty()) {
            "No workers registered: call worker(type) { ... } at least once"
        }

        return JabbitConfiguration(
            registrations = registrations.toMap(),
            listeners = listeners.toList(),
            logger = logger,
            maxConcurrentJobs = maxConcurrentJobs,
            finishedJobRetention = finishedJobRetention
        )
    }

    private companion object {

        const val DEFAULT_MAX_CONCURRENT_JOBS = 4

        val DEFAULT_FINISHED_JOB_RETENTION = 7.days
    }
}

/**
 * Android side of the `jabbit { }` builder.
 *
 * Android needs nothing configured: `WorkManager` owns the queue, and the application context is
 * picked up automatically. The block exists so that shared setup code reads the same on every
 * platform, and so later Android-only settings have a home.
 */
@JabbitDsl
public class AndroidScope internal constructor()

/**
 * iOS side of the `jabbit { }` builder. Mirrors [JabbitIosOptions].
 */
@JabbitDsl
public class IosScope internal constructor() {

    /**
     * Identifier of the `BGProcessingTask` used to wake the app up for pending jobs. It must also
     * be listed under `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`.
     */
    public var backgroundTaskIdentifier: String? = null

    /**
     * Where the job queue is persisted. Defaults to `NSUserDefaults`.
     */
    public var storage: JabbitStorage? = null

    /**
     * Free space below which `requiresStorageNotLow` stops being satisfied.
     */
    public var lowStorageThresholdBytes: Long = JabbitDefaults.DEVICE_LOW_STORAGE_BYTES

    /**
     * Battery level below which `requiresBatteryNotLow` stops being satisfied.
     */
    public var lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
}

/**
 * Desktop side of the `jabbit { }` builder. Mirrors [JabbitDesktopOptions].
 */
@JabbitDsl
public class DesktopScope internal constructor() {

    /**
     * Name of the application, used to keep this queue apart from other applications on the same
     * machine. Required when running on the desktop.
     */
    public var applicationName: String? = null

    /**
     * Directory holding the persisted queue and the single instance lock. Defaults to the
     * directory the operating system reserves for [applicationName].
     */
    public var storageDirectory: String? = null

    /**
     * Where the job queue is persisted. Defaults to a file under [storageDirectory].
     */
    public var storage: JabbitStorage? = null

    /**
     * Refuse to start when another process already owns the storage directory.
     */
    public var singleInstanceLock: Boolean = true

    /**
     * Read the power source of the machine, so charging and battery constraints mean something.
     */
    public var readPowerSource: Boolean = true

    /**
     * How often the machine is polled for connectivity, power and free space.
     */
    public var pollInterval: Duration? = null

    /**
     * Free space below which `requiresStorageNotLow` stops being satisfied.
     */
    public var lowStorageThresholdBytes: Long = JabbitDefaults.DEVICE_LOW_STORAGE_BYTES

    /**
     * Battery level below which `requiresBatteryNotLow` stops being satisfied.
     */
    public var lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
}

/**
 * Browser side of the `jabbit { }` builder. Mirrors [JabbitBrowserOptions].
 */
@JabbitDsl
public class BrowserScope internal constructor() {

    /**
     * Name that scopes this queue against other Jabbit queues of the same origin.
     */
    public var queueName: String = JabbitDefaults.BROWSER_QUEUE_NAME

    /**
     * Where the job queue is persisted. Defaults to IndexedDB.
     */
    public var storage: JabbitStorage? = null

    /**
     * Tag of the one-shot Background Sync registration, or `null` to never register one.
     */
    public var backgroundSyncTag: String? = JabbitDefaults.BROWSER_SYNC_TAG

    /**
     * Tag of the Periodic Background Sync registration, or `null` to disable it.
     */
    public var periodicSyncTag: String? = null

    /**
     * Interval requested for [periodicSyncTag].
     */
    public var periodicSyncInterval: Duration? = null

    /**
     * Ask the browser to keep this origin's storage when the instance is created.
     */
    public var requestPersistentStorage: Boolean = false

    /**
     * Elect a single tab to run jobs.
     */
    public var coordinateTabs: Boolean = true

    /**
     * Free quota below which `requiresStorageNotLow` stops being satisfied.
     */
    public var lowStorageThresholdBytes: Long = JabbitDefaults.BROWSER_LOW_STORAGE_BYTES

    /**
     * Battery level below which `requiresBatteryNotLow` stops being satisfied.
     */
    public var lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
}
