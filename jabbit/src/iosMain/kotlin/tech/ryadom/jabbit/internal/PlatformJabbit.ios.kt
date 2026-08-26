package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitIosOptions
import tech.ryadom.jabbit.JabbitScope
import tech.ryadom.jabbit.UserDefaultsJabbitStorage

internal actual fun createPlatformJabbit(jabbitScope: JabbitScope): Jabbit {
    val configuration = jabbitScope.buildConfiguration()
    val options = JabbitIosOptions(
        backgroundTaskIdentifier = jabbitScope.iosOptions.backgroundTaskIdentifier,
        storage = jabbitScope.iosOptions.storage ?: UserDefaultsJabbitStorage(),
        lowStorageThresholdBytes = jabbitScope.iosOptions.lowStorageThresholdBytes,
        lowBatteryThreshold = jabbitScope.iosOptions.lowBatteryThreshold
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val deviceState = IosDeviceStateProvider(options)
    val coordinator = IosBackgroundCoordinator(
        options = options,
        logger = configuration.logger,
        scope = scope,
        deviceState = deviceState
    )

    val engine = JobEngine(
        configuration = configuration,
        storage = JsonJobRecordStorage(options.storage, configuration.logger),
        deviceStateProvider = deviceState,
        scope = scope,
        wakeUpPlanner = coordinator::plan
    )

    deviceState.start()
    coordinator.install(
        runUntilIdle = { engine.runUntilIdle() },
        wakeUp = { engine.wakeUp() }
    )
    engine.start()

    return EngineJabbit(engine)
}
