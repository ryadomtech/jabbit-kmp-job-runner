package tech.ryadom.jabbit

/**
 * Network condition a job requires before it may run.
 *
 * Android maps every entry onto the matching `androidx.work.NetworkType`. iOS derives the state
 * from `NWPathMonitor`, where [UNMETERED] and [METERED] are resolved through the "expensive" flag
 * of the current path (cellular and personal hotspot count as metered), and [NOT_ROAMING] behaves
 * like [CONNECTED] because the system exposes no roaming flag.
 */
public enum class NetworkType {

    /**
     * The job runs regardless of connectivity.
     */
    NOT_REQUIRED,

    /**
     * Any working connection is enough.
     */
    CONNECTED,

    /**
     * Only an unmetered connection, typically Wi-Fi.
     */
    UNMETERED,

    /**
     * Only a metered connection, typically cellular.
     */
    METERED,

    /**
     * Any working connection that is not roaming.
     */
    NOT_ROAMING
}

/**
 * Device conditions that must hold before a job is allowed to run.
 *
 * Constraints are re-evaluated by the scheduler, so a job whose constraints stop being satisfied
 * while it is running is stopped and retried later.
 *
 * ```
 * val constraints = constraints {
 *     requiredNetworkType = NetworkType.UNMETERED
 *     requiresCharging = true
 * }
 * ```
 */
public data class Constraints(

    /**
     * Connectivity the job requires.
     */
    public val requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED,

    /**
     * Requires the device to be plugged into power.
     */
    public val requiresCharging: Boolean = false,

    /**
     * Requires the battery to be above the system's low-battery threshold.
     */
    public val requiresBatteryNotLow: Boolean = false,

    /**
     * Requires free storage above the system's low-storage threshold.
     *
     * On iOS the threshold is `JabbitIosOptions.lowStorageThresholdBytes`.
     */
    public val requiresStorageNotLow: Boolean = false,

    /**
     * Requires the device to be idle.
     *
     * On iOS this is satisfied only while the job runs inside a background processing task granted
     * by `BGTaskScheduler`, which the system schedules when the device is idle and charging.
     */
    public val requiresDeviceIdle: Boolean = false
) {

    /**
     * Builder form of [Constraints], convenient from Swift and Java.
     */
    public class Builder {

        private var requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED
        private var requiresCharging: Boolean = false
        private var requiresBatteryNotLow: Boolean = false
        private var requiresStorageNotLow: Boolean = false
        private var requiresDeviceIdle: Boolean = false

        /**
         * Sets the required connectivity.
         */
        public fun setRequiredNetworkType(networkType: NetworkType): Builder = apply {
            requiredNetworkType = networkType
        }

        /**
         * Requires the device to be plugged into power.
         */
        public fun setRequiresCharging(required: Boolean): Builder = apply {
            requiresCharging = required
        }

        /**
         * Requires the battery not to be low.
         */
        public fun setRequiresBatteryNotLow(required: Boolean): Builder = apply {
            requiresBatteryNotLow = required
        }

        /**
         * Requires free storage not to be low.
         */
        public fun setRequiresStorageNotLow(required: Boolean): Builder = apply {
            requiresStorageNotLow = required
        }

        /**
         * Requires the device to be idle.
         */
        public fun setRequiresDeviceIdle(required: Boolean): Builder = apply {
            requiresDeviceIdle = required
        }

        /**
         * Builds the constraints.
         */
        public fun build(): Constraints = Constraints(
            requiredNetworkType = requiredNetworkType,
            requiresCharging = requiresCharging,
            requiresBatteryNotLow = requiresBatteryNotLow,
            requiresStorageNotLow = requiresStorageNotLow,
            requiresDeviceIdle = requiresDeviceIdle
        )
    }

    public companion object {

        /**
         * Constraints that are always satisfied.
         */
        public val NONE: Constraints = Constraints()
    }
}

/**
 * Scope of the [constraints] builder function.
 */
public class ConstraintsScope internal constructor() {

    /**
     * Connectivity the job requires.
     */
    public var requiredNetworkType: NetworkType = NetworkType.NOT_REQUIRED

    /**
     * Requires the device to be plugged into power.
     */
    public var requiresCharging: Boolean = false

    /**
     * Requires the battery to be above the system's low-battery threshold.
     */
    public var requiresBatteryNotLow: Boolean = false

    /**
     * Requires free storage above the system's low-storage threshold.
     */
    public var requiresStorageNotLow: Boolean = false

    /**
     * Requires the device to be idle.
     */
    public var requiresDeviceIdle: Boolean = false

    internal fun build(): Constraints = Constraints(
        requiredNetworkType = requiredNetworkType,
        requiresCharging = requiresCharging,
        requiresBatteryNotLow = requiresBatteryNotLow,
        requiresStorageNotLow = requiresStorageNotLow,
        requiresDeviceIdle = requiresDeviceIdle
    )
}

/**
 * Builds [Constraints] with a DSL.
 *
 * ```
 * val constraints = constraints {
 *     requiredNetworkType = NetworkType.CONNECTED
 *     requiresBatteryNotLow = true
 * }
 * ```
 */
public fun constraints(block: ConstraintsScope.() -> Unit): Constraints =
    ConstraintsScope().apply(block).build()
