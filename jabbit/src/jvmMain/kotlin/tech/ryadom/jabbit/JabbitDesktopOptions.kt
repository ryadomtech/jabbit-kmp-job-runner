package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JabbitDefaults
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Desktop specific knobs of [createJabbit].
 */
internal class JabbitDesktopOptions(

    /**
     * Name of the application, used to keep this queue apart from the queues of other applications
     * on the same machine.
     */
    val applicationName: String,

    /**
     * Directory holding the persisted queue and the single instance lock.
     */
    val storageDirectory: Path = defaultStorageDirectory(applicationName),

    storage: JabbitStorage? = null,

    /**
     * Refuse to start when another process already owns [storageDirectory].
     *
     * Two processes sharing one queue would run the same job twice and overwrite each other's
     * changes, so this is on by default. Turn it off only when the queue is per process anyway.
     */
    val singleInstanceLock: Boolean = true,

    /**
     * Read the power source of the machine, so [Constraints.requiresCharging] and
     * [Constraints.requiresBatteryNotLow] mean something on a laptop.
     *
     * Supported on Linux, through `/sys/class/power_supply`, and on macOS, by running `pmset`
     * at most once per [pollInterval]. Elsewhere, and with this turned off, both constraints are
     * always satisfied.
     */
    val readPowerSource: Boolean = true,

    /**
     * How often the machine is polled for connectivity, power and free space.
     *
     * The desktop exposes no callbacks for any of it, so this is what decides how quickly a job
     * notices that its constraints became satisfiable.
     */
    val pollInterval: Duration = JabbitDefaults.DESKTOP_POLL_SECONDS.seconds,

    /**
     * Free space on the volume of [storageDirectory] below which
     * [Constraints.requiresStorageNotLow] stops being satisfied.
     */
    val lowStorageThresholdBytes: Long = JabbitDefaults.DEVICE_LOW_STORAGE_BYTES,

    /**
     * Battery level, from `0.0` to `1.0`, below which [Constraints.requiresBatteryNotLow] stops
     * being satisfied while the machine is not plugged in.
     */
    val lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
) {

    /**
     * Where the job queue is persisted. Defaults to `jobs.json` inside [storageDirectory].
     */
    val storage: JabbitStorage = storage
        ?: FileJabbitStorage(storageDirectory.resolve(QUEUE_FILE_NAME))

    init {
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
        require(pollInterval.isPositive()) { "pollInterval must be positive, was $pollInterval" }
    }

    internal val lockFile: Path get() = storageDirectory.resolve(LOCK_FILE_NAME)

    private companion object {

        const val QUEUE_FILE_NAME = "jobs.json"

        const val LOCK_FILE_NAME = "jabbit.lock"
    }
}

/**
 * Returns the directory this operating system reserves for the data of [applicationName]:
 * `%APPDATA%` on Windows, `~/Library/Application Support` on macOS, and `$XDG_DATA_HOME` elsewhere.
 */
internal fun defaultStorageDirectory(applicationName: String): Path {
    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").orEmpty().lowercase()

    val base = when {
        os.contains("win") -> System.getenv("APPDATA")?.let { Paths.get(it) }
            ?: Paths.get(home, "AppData", "Roaming")

        os.contains("mac") || os.contains("darwin") ->
            Paths.get(home, "Library", "Application Support")

        else -> System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            ?: Paths.get(home, ".local", "share")
    }

    return base.resolve(applicationName).resolve("jabbit")
}
