package tech.ryadom.jabbit

/**
 * iOS specific knobs of [createJabbit].
 *
 * @see createJabbit
 */
public class JabbitIosOptions(

    /**
     * Identifier of the `BGProcessingTask` used to wake the app up for pending jobs.
     *
     * The identifier must also be listed under `BGTaskSchedulerPermittedIdentifiers` in
     * `Info.plist`. Without it Jabbit only runs jobs while the app is in the foreground or inside
     * the short window the system grants after the app is backgrounded.
     */
    public val backgroundTaskIdentifier: String? = null,

    /**
     * Where the job queue is persisted.
     */
    public val storage: JabbitStorage = UserDefaultsJabbitStorage(),

    /**
     * Free space below which [Constraints.requiresStorageNotLow] stops being satisfied.
     */
    public val lowStorageThresholdBytes: Long = DEFAULT_LOW_STORAGE_BYTES,

    /**
     * Battery level, from `0.0` to `1.0`, below which [Constraints.requiresBatteryNotLow] stops
     * being satisfied while the device is not charging.
     */
    public val lowBatteryThreshold: Float = DEFAULT_LOW_BATTERY_THRESHOLD
) {

    private companion object {

        const val DEFAULT_LOW_STORAGE_BYTES = 500L * 1024L * 1024L

        const val DEFAULT_LOW_BATTERY_THRESHOLD = 0.15f
    }
}
