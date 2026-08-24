package tech.ryadom.jabbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.ryadom.jabbit.internal.BrowserDeviceStateProvider
import tech.ryadom.jabbit.internal.BrowserJabbit
import tech.ryadom.jabbit.internal.BrowserWakeUpPlanner
import tech.ryadom.jabbit.internal.JobEngine
import tech.ryadom.jabbit.internal.JsonJobRecordStorage
import tech.ryadom.jabbit.internal.SystemClock
import tech.ryadom.jabbit.internal.TabCoordinator

/**
 * Creates the browser implementation of [Jabbit].
 *
 * The browser has no system job scheduler, so Jabbit owns the queue: requests are persisted through
 * [JabbitBrowserOptions.storage] and executed by the page itself. Jobs whose constraints are
 * satisfied run right away; the rest wait, and survive a reload because the queue is persisted.
 *
 * Call this once, as early as the app starts:
 *
 * ```
 * val jabbit = createJabbit(
 *     configuration = jabbitConfiguration {
 *         worker(SyncWorker.NAME) { SyncWorker(api) }
 *     },
 *     options = JabbitBrowserOptions(periodicSyncTag = "com.example.refresh")
 * )
 * ```
 *
 * **Several tabs.** Only one tab runs jobs at a time, elected through the Web Locks API. The other
 * tabs forward what they enqueue to it and mirror its state, so [getJobInfoFlow] and friends report
 * the same thing everywhere. When the elected tab closes, another one takes over. Set
 * [JabbitBrowserOptions.coordinateTabs] to `false` to let every tab run its own jobs.
 *
 * **After the last tab closes** jobs only continue if a service worker running
 * [startJabbitServiceWorker] is registered; see its documentation for what the browser grants.
 */
public fun createJabbit(
    configuration: JabbitConfiguration,
    options: JabbitBrowserOptions = JabbitBrowserOptions()
): Jabbit {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val storage = JsonJobRecordStorage(options.storage, configuration.logger)
    val deviceState = BrowserDeviceStateProvider(options)
    val planner = BrowserWakeUpPlanner(options, configuration.logger)

    val engine = JobEngine(
        configuration = configuration,
        storage = storage,
        deviceStateProvider = deviceState,
        scope = scope,
        wakeUpPlanner = planner
    )

    deviceState.start()

    return BrowserJabbit(
        configuration = configuration,
        options = options,
        engine = engine,
        storage = storage,
        coordinator = TabCoordinator(options.channelName, configuration.logger),
        planner = planner,
        scope = scope,
        clock = SystemClock
    ).also { it.start() }
}
