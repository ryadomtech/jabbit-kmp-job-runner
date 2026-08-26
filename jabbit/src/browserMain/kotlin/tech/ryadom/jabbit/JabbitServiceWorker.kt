package tech.ryadom.jabbit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.ryadom.jabbit.internal.BrowserDeviceStateProvider
import tech.ryadom.jabbit.internal.JobEngine
import tech.ryadom.jabbit.internal.JsonJobRecordStorage
import tech.ryadom.jabbit.internal.completeExtendableEvent
import tech.ryadom.jabbit.internal.hasActiveClients
import tech.ryadom.jabbit.internal.listenExtendableEvent
import tech.ryadom.jabbit.internal.toOptions

/**
 * Runs pending jobs inside a service worker, so they continue after the last page is closed.
 *
 * Call this from the top level of the service worker script — a Kotlin/JS or Kotlin/Wasm bundle of
 * its own — with the same description the page uses, because the worker has to be able to build the
 * same workers:
 *
 * ```
 * fun main() {
 *     startJabbitServiceWorker {
 *         worker(SyncJob) { SyncWorker(api) }
 *
 *         browser {
 *             periodicSyncTag = "com.example.refresh"
 *         }
 *     }
 * }
 * ```
 *
 * The `browser { }` block must match the page's: the queue is only shared when both sides use the
 * same storage and the same sync tags. That storage must be reachable from a worker, which rules
 * out [LocalStorageJabbitStorage].
 *
 * What the browser actually grants:
 *
 * - `sync` — one shot, fired when connectivity returns after the app went offline. Chromium only.
 * - `periodicsync` — fired on a cadence the browser chooses, only for an installed app it considers
 *   engaging, and only after the `periodic-background-sync` permission is granted. Chromium only.
 *
 * Neither event fires in Safari or Firefox, where jobs run only while a page is open. Nothing here
 * throws when the events are unavailable; the registrations are simply skipped.
 *
 * While any page of the app is open the worker stays out of the way and lets the page run the
 * queue, so the same job is never started twice.
 */
public fun startJabbitServiceWorker(configure: JabbitScope.() -> Unit) {
    val jabbitScope = JabbitScope().apply(configure)
    val configuration = jabbitScope.buildConfiguration()
    val options = jabbitScope.browserOptions.toOptions()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val deviceState = BrowserDeviceStateProvider(options)

    val engine = JobEngine(
        configuration = configuration,
        storage = JsonJobRecordStorage(options.storage, configuration.logger),
        deviceStateProvider = deviceState,
        scope = scope
    )

    deviceState.start()

    val drain: (Int) -> Unit = { eventId ->
        hasActiveClients { pageIsOpen ->
            if (pageIsOpen) {
                configuration.logger.log(
                    JabbitLogger.Level.DEBUG,
                    "A page is open, leaving the queue to it",
                    null
                )
                completeExtendableEvent(eventId)
            } else {
                scope.launch {
                    try {
                        engine.reloadFromStorage()
                        engine.runUntilIdle()
                    } finally {
                        completeExtendableEvent(eventId)
                    }
                }
            }
        }
    }

    options.backgroundSyncTag?.let { tag -> listenExtendableEvent("sync", tag, drain) }
    options.periodicSyncTag?.let { tag -> listenExtendableEvent("periodicsync", tag, drain) }
}
