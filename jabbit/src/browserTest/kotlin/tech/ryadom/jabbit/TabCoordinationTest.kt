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
        val storageName = "jabbit-test-${Random.nextInt()}"
        val ranOnElected = CompletableDeferred<String>()

        val elected = newJabbit(storageName) {
            worker("shared") {
                JabbitWorker { job ->
                    ranOnElected.complete(job.id.value)
                    JobResult.success(jobDataOf("ranBy" to "elected"))
                }
            }
        }

        withContext(Dispatchers.Default) { delay(500) }

        val other = newJabbit(storageName) {
            worker("shared") {
                JabbitWorker { JobResult.success(jobDataOf("ranBy" to "other")) }
            }
        }

        val request = oneTimeJob("shared")
        other.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                assertEquals(request.id.value, ranOnElected.await())

                while (other.getJobInfo(request.id)?.state != JobState.SUCCEEDED) {
                    delay(50)
                }
            }
        }

        assertEquals("elected", other.getJobInfo(request.id)?.outputData?.getString("ranBy"))
        assertEquals("elected", elected.getJobInfo(request.id)?.outputData?.getString("ranBy"))
    }

    private fun newJabbit(
        databaseName: String,
        configure: JabbitConfiguration.Builder.() -> Unit
    ): Jabbit = createJabbit(
        configuration = JabbitConfiguration.Builder().apply(configure).build(),
        options = JabbitBrowserOptions(
            queueName = databaseName,
            storage = IndexedDbJabbitStorage(databaseName = databaseName),
            backgroundSyncTag = null,
            coordinateTabs = true
        )
    )
}
