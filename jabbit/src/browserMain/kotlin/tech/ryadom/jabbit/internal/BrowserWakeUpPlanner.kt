package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.JabbitBrowserOptions
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.debug

private const val SYNC_REGISTRATION_THROTTLE_MILLIS = 30_000L

internal class BrowserWakeUpPlanner(
    private val options: JabbitBrowserOptions,
    private val logger: JabbitLogger,
    private val clock: JabbitClock = SystemClock
) : WakeUpPlanner {

    private var lastRegisteredAt = 0L

    override fun planWakeUp(plan: WakeUpPlan?) {
        val tag = options.backgroundSyncTag ?: return
        if (plan == null) return

        val now = clock.nowMillis()
        if (now - lastRegisteredAt < SYNC_REGISTRATION_THROTTLE_MILLIS) return
        lastRegisteredAt = now

        registerOneShotSync(tag) { registered ->
            logger.debug(
                if (registered) {
                    "Registered background sync '$tag' for pending jobs"
                } else {
                    "Background sync is unavailable, jobs will run while a page is open"
                }
            )
        }
    }

    fun registerPeriodicWakeUp() {
        val tag = options.periodicSyncTag ?: return
        registerPeriodicSync(tag, options.periodicSyncInterval.inWholeMilliseconds.toDouble()) {
            logger.debug(
                if (it) {
                    "Registered periodic background sync '$tag'"
                } else {
                    "Periodic background sync is unavailable"
                }
            )
        }
    }
}
