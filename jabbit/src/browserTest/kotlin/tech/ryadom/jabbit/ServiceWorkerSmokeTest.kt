package tech.ryadom.jabbit

import kotlin.random.Random
import kotlin.test.Test

class ServiceWorkerSmokeTest {

    @Test
    fun startsOutsideAServiceWorkerWithoutFailing() {
        startJabbitServiceWorker(
            configuration = jabbitConfiguration {
                worker("noop") { JabbitWorker { JobResult.success() } }
            },
            options = JabbitBrowserOptions(
                storage = IndexedDbJabbitStorage(
                    databaseName = "jabbit-test-${Random.nextInt()}"
                ),
                backgroundSyncTag = "jabbit-test-sync",
                periodicSyncTag = "jabbit-test-periodic"
            )
        )
    }
}
