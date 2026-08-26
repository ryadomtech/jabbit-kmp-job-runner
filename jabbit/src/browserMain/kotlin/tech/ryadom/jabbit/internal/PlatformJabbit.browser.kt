package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.ryadom.jabbit.IndexedDbJabbitStorage
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitBrowserOptions
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.JabbitScope
import tech.ryadom.jabbit.requestPersistentStorage
import kotlin.time.Duration.Companion.hours

internal actual fun createPlatformJabbit(jabbitScope: JabbitScope): Jabbit {
    val configuration = jabbitScope.buildConfiguration()
    val options = jabbitScope.browserOptions.toOptions()

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

    if (options.requestPersistentStorage) {
        scope.launch {
            val granted = requestPersistentStorage()
            configuration.logger.log(
                JabbitLogger.Level.DEBUG,
                if (granted) {
                    "The browser will keep the job queue"
                } else {
                    "The browser may evict the job queue when space runs low"
                },
                null
            )
        }
    }

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

internal fun tech.ryadom.jabbit.BrowserScope.toOptions(): JabbitBrowserOptions =
    JabbitBrowserOptions(
        queueName = queueName,
        storage = storage ?: IndexedDbJabbitStorage(),
        backgroundSyncTag = backgroundSyncTag,
        periodicSyncTag = periodicSyncTag,
        periodicSyncInterval = periodicSyncInterval
            ?: JabbitDefaults.BROWSER_PERIODIC_SYNC_HOURS.hours,
        requestPersistentStorage = requestPersistentStorage,
        coordinateTabs = coordinateTabs,
        lowStorageThresholdBytes = lowStorageThresholdBytes,
        lowBatteryThreshold = lowBatteryThreshold
    )
