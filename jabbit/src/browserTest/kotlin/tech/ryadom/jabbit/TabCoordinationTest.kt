package tech.ryadom.jabbit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class TabCoordinationTest {

    @Test
    fun theElectedInstanceRunsWorkEnqueuedByAnotherOne() = runTest {
        val queue = "jabbit-test-${Random.nextInt()}"
        val ranOnElected = CompletableDeferred<String>()

        val elected = newJabbit(queue) {
            worker(GreetJob) {
                JabbitWorker { job ->
                    ranOnElected.complete(job.id.value)
                    JobResult.success("elected")
                }
            }
        }

        withContext(Dispatchers.Default) { delay(500) }

        val other = newJabbit(queue) {
            worker(GreetJob) { JabbitWorker { JobResult.success("other") } }
        }

        val request = oneTimeJob(GreetJob, Greeting("shared"))
        other.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                assertEquals(request.id.value, ranOnElected.await())

                while (other.getJobInfo(request.id)?.state != JobState.SUCCEEDED) {
                    delay(50)
                }
            }
        }

        assertEquals("elected", other.getJobInfo(request.id)?.output(GreetJob))
        assertEquals("elected", elected.getJobInfo(request.id)?.output(GreetJob))
    }

    private fun newJabbit(queue: String, configure: JabbitScope.() -> Unit): Jabbit = jabbit {
        configure()
        browser {
            queueName = queue
            storage = IndexedDbJabbitStorage(databaseName = queue)
            backgroundSyncTag = null
            coordinateTabs = true
        }
    }
}
