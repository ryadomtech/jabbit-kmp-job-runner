package tech.ryadom.jabbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.ryadom.jabbit.internal.DesktopDeviceStateProvider
import tech.ryadom.jabbit.internal.DesktopJabbitImpl
import tech.ryadom.jabbit.internal.EngineJabbit
import tech.ryadom.jabbit.internal.JobEngine
import tech.ryadom.jabbit.internal.JsonJobRecordStorage
import tech.ryadom.jabbit.internal.SingleInstanceLock

/**
 * Creates the desktop implementation of [Jabbit].
 *
 * A desktop process is the only thing that can run its own background work, so Jabbit owns the
 * queue here: requests are persisted through [JabbitDesktopOptions.storage] — a file under
 * [JabbitDesktopOptions.storageDirectory] by default — and executed while the application runs.
 * Anything still pending when the application exits is picked up on the next start, and a job that
 * was interrupted mid-run is retried.
 *
 * ```
 * val jabbit = createJabbit(
 *     configuration = jabbitConfiguration {
 *         worker(SyncWorker.NAME) { SyncWorker(api) }
 *     },
 *     options = JabbitDesktopOptions(applicationName = "Example")
 * )
 * ```
 *
 * Unlike Android and iOS, nothing here survives the process: there is no system service to hand the
 * queue to. Treat the desktop scheduler as "runs while the application is open, and never forgets
 * what it has not finished".
 *
 * @throws IllegalStateException when [JabbitDesktopOptions.singleInstanceLock] is on and another
 * process already owns the storage directory.
 */
public fun createJabbit(
    configuration: JabbitConfiguration,
    options: JabbitDesktopOptions
): DesktopJabbit {
    val lock = if (options.singleInstanceLock) SingleInstanceLock(options.lockFile) else null
    check(lock == null || lock.tryAcquire(configuration.logger)) {
        "Another process is already running Jabbit in ${options.storageDirectory}. " +
            "Point this instance at a different storageDirectory, or turn off singleInstanceLock " +
            "if both processes are meant to keep their own queue."
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val deviceState = DesktopDeviceStateProvider(options)

    val engine = JobEngine(
        configuration = configuration,
        storage = JsonJobRecordStorage(options.storage, configuration.logger),
        deviceStateProvider = deviceState,
        scope = scope
    )

    deviceState.start(scope)
    engine.start()

    return DesktopJabbitImpl(EngineJabbit(engine), scope, lock)
}
