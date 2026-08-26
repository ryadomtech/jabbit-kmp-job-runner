package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JabbitDefaults
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Browser specific knobs of [createJabbit] and [startJabbitServiceWorker].
 *
 * The same options must be passed on both sides — the page and the service worker — because they
 * decide which storage the queue lives in and which sync tags carry it.
 */
internal class JabbitBrowserOptions(

    /**
     * Name that scopes this queue against other Jabbit queues of the same origin.
     *
     * It decides which tabs coordinate with each other, so two independent queues served from one
     * origin must not share it.
     */
    val queueName: String = JabbitDefaults.BROWSER_QUEUE_NAME,

    /**
     * Where the job queue is persisted.
     *
     * Keep the default [IndexedDbJabbitStorage] if jobs should survive the page being closed:
     * `localStorage` is invisible to a service worker.
     */
    val storage: JabbitStorage = IndexedDbJabbitStorage(),

    /**
     * Tag of the one-shot Background Sync registration used to wake a closed app up once
     * connectivity is back.
     *
     * Requires a registered service worker running [startJabbitServiceWorker]. Set to `null` to
     * never register one.
     */
    val backgroundSyncTag: String? = JabbitDefaults.BROWSER_SYNC_TAG,

    /**
     * Tag of the Periodic Background Sync registration used to wake a closed app up regularly.
     *
     * Chromium only, and only for an installed app the browser considers engaging. `null` disables
     * it.
     */
    val periodicSyncTag: String? = null,

    /**
     * Interval requested for [periodicSyncTag]. The browser treats it as a lower bound and picks
     * the real cadence itself.
     */
    val periodicSyncInterval: Duration =
        JabbitDefaults.BROWSER_PERIODIC_SYNC_HOURS.hours,

    /**
     * Ask the browser to keep this origin's storage when [createJabbit] runs.
     *
     * Off by default because Firefox turns the request into a permission prompt, which an
     * application usually wants to raise at a moment of its own choosing — call
     * [requestPersistentStorage] then instead. Leaving it off means the browser may evict the
     * queue when the device runs low on space.
     */
    val requestPersistentStorage: Boolean = false,

    /**
     * Elect a single tab to run jobs, so several open tabs of the same app do not execute the same
     * job at once.
     *
     * Uses the Web Locks API. Where it is missing, every tab runs its own jobs.
     */
    val coordinateTabs: Boolean = true,

    /**
     * Free quota below which [Constraints.requiresStorageNotLow] stops being satisfied, measured as
     * `quota - usage` reported by `navigator.storage.estimate()`.
     */
    val lowStorageThresholdBytes: Long = JabbitDefaults.BROWSER_LOW_STORAGE_BYTES,

    /**
     * Battery level, from `0.0` to `1.0`, below which [Constraints.requiresBatteryNotLow] stops
     * being satisfied while the device is not charging.
     *
     * Only Chromium exposes the battery; elsewhere the constraint is always satisfied.
     */
    val lowBatteryThreshold: Float = JabbitDefaults.LOW_BATTERY_THRESHOLD
) {

    /**
     * Name of the Web Lock electing the tab that runs this queue.
     */
    internal val leaderLockName: String get() = "tech.ryadom.jabbit.leader.$queueName"

    /**
     * Name of the channel tabs use to share this queue.
     */
    internal val channelName: String get() = "tech.ryadom.jabbit.queue.$queueName"
}
