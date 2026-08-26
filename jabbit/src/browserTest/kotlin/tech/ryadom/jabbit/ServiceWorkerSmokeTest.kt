package tech.ryadom.jabbit

import kotlin.random.Random
import kotlin.test.Test

class ServiceWorkerSmokeTest {

    @Test
    fun startsOutsideAServiceWorkerWithoutFailing() {
        startJabbitServiceWorker {
            worker(OkJob) { JabbitWorker { JobResult.success(1) } }

            browser {
                queueName = "jabbit-test-${Random.nextInt()}"
                backgroundSyncTag = "jabbit-test-sync"
                periodicSyncTag = "jabbit-test-periodic"
            }
        }
    }
}
