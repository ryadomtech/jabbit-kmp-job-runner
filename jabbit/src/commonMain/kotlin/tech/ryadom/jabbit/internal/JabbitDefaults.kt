package tech.ryadom.jabbit.internal

internal object JabbitDefaults {

    const val LOW_BATTERY_THRESHOLD: Float = 0.15f

    const val DEVICE_LOW_STORAGE_BYTES: Long = 500L * 1024L * 1024L

    const val BROWSER_LOW_STORAGE_BYTES: Long = 50L * 1024L * 1024L

    const val BROWSER_QUEUE_NAME: String = "jabbit"

    const val BROWSER_SYNC_TAG: String = "tech.ryadom.jabbit.sync"

    const val BROWSER_PERIODIC_SYNC_HOURS: Long = 12L

    const val DESKTOP_POLL_SECONDS: Long = 30L
}
