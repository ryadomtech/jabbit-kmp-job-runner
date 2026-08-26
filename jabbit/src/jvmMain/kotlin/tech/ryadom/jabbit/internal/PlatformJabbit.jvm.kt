package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitDesktopOptions
import tech.ryadom.jabbit.JabbitScope
import tech.ryadom.jabbit.defaultStorageDirectory
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

internal actual fun createPlatformJabbit(jabbitScope: JabbitScope): Jabbit {
    val desktop = jabbitScope.desktopOptions
    val applicationName = checkNotNull(desktop.applicationName) {
        "Running on the desktop needs jabbit { desktop { applicationName = \"...\" } }, which " +
            "decides where the queue is kept."
    }

    val options = JabbitDesktopOptions(
        applicationName = applicationName,
        storageDirectory = desktop.storageDirectory
            ?.let { Paths.get(it) }
            ?: defaultStorageDirectory(applicationName),
        storage = desktop.storage,
        singleInstanceLock = desktop.singleInstanceLock,
        readPowerSource = desktop.readPowerSource,
        pollInterval = desktop.pollInterval ?: JabbitDefaults.DESKTOP_POLL_SECONDS.seconds,
        lowStorageThresholdBytes = desktop.lowStorageThresholdBytes,
        lowBatteryThreshold = desktop.lowBatteryThreshold
    )

    val configuration = jabbitScope.buildConfiguration()
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
