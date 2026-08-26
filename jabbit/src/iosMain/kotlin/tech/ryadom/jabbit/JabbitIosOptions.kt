package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JabbitDefaults

/**
 * iOS specific knobs of [createJabbit].
 *
 * @see createJabbit
 */
internal class JabbitIosOptions(

    /**
     * Identifier of the `BGProcessingTask` used to wake the app up for pending jobs.
     *
     * The identifier must also be listed under `BGTaskSchedulerPermittedIdentifiers` in
     * `Info.plist`. Without it Jabbit only runs jobs while the app is in the foreground or inside
     * the short window the system grants after the app is backgrounded.
     */
    val backgroundTaskIdentifier: String? = null,

    /**
     * Where the job queue is persisted.
     */
    val storage: JabbitStorage = UserDefaultsJabbitStorage(),

    /**
     * Free space below which [Constraints.requiresStorageNotLow] stops being satisfied.
     */
    val lowStorageThresholdBytes: Long = JabbitDefaults.DEVICE_LOW_STORAGE_BYTES,

    /**
     * Battery level, from `0.0` to `1.0`, below which [Constraints.requiresBatteryNotLow] stops
     * being satisfied while the device is not charging.
     */
    val lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
)
