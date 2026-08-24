package tech.ryadom.jabbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.ryadom.jabbit.internal.IosBackgroundCoordinator
import tech.ryadom.jabbit.internal.IosDeviceStateProvider
import tech.ryadom.jabbit.internal.IosJabbit
import tech.ryadom.jabbit.internal.JobEngine
import tech.ryadom.jabbit.internal.JsonJobRecordStorage

/**
 * Creates the iOS implementation of [Jabbit].
 *
 * iOS has no system service that owns a queue of application jobs, so Jabbit keeps that queue
 * itself: requests are persisted through [JabbitIosOptions.storage] and executed while the app is
 * running, during the seconds the system grants after the app is backgrounded, and inside the
 * background windows granted by `BGTaskScheduler`.
 *
 * Call this from `application(_:didFinishLaunchingWithOptions:)` on the main thread and **before it
 * returns**, because `BGTaskScheduler` refuses handlers registered after launch:
 *
 * ```swift
 * func application(
 *     _ application: UIApplication,
 *     didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
 * ) -> Bool {
 *     let configuration = JabbitConfigurationBuilder()
 *         .worker(name: "sync") { SyncWorker(api: api) }
 *         .build()
 *
 *     jabbit = JabbitIosKt.createJabbit(
 *         configuration: configuration,
 *         options: JabbitIosOptions(
 *             backgroundTaskIdentifier: "tech.ryadom.example.jabbit",
 *             storage: UserDefaultsJabbitStorage(),
 *             lowStorageThresholdBytes: 500 * 1024 * 1024,
 *             lowBatteryThreshold: 0.15
 *         )
 *     )
 *     return true
 * }
 * ```
 *
 * For jobs to run while the app is not in the foreground, declare `processing` in
 * `UIBackgroundModes` and list [JabbitIosOptions.backgroundTaskIdentifier] under
 * `BGTaskSchedulerPermittedIdentifiers` in `Info.plist`. The system decides when those windows
 * happen; a job whose constraints are satisfied while the app is open runs immediately.
 */
public fun createJabbit(
    configuration: JabbitConfiguration,
    options: JabbitIosOptions = JabbitIosOptions()
): Jabbit {
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

    return IosJabbit(engine)
}
