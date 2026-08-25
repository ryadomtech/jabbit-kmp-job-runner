package tech.ryadom.jabbit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class BrowserJabbitTest {

    @Test
    fun runsAJobAndReportsItsOutcome() = runTest {
        val finished = CompletableDeferred<JobData>()
        val jabbit = newJabbit {
            worker("greet") {
                JabbitWorker { job ->
                    val name = job.inputData.getString("name")
                    val output = jobDataOf("greeting" to "hello $name")
                    finished.complete(output)
                    JobResult.success(output)
                }
            }
        }

        val request = oneTimeJob("greet") { setInputData(jobDataOf("name" to "world")) }
        jabbit.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                assertEquals("hello world", finished.await().getString("greeting"))
                awaitState(jabbit, request.id, JobState.SUCCEEDED)
            }
        }

        assertEquals(
            "hello world",
            jabbit.getJobInfo(request.id)?.outputData?.getString("greeting")
        )
    }

    @Test
    fun waitsForConstraintsItCannotSatisfy() = runTest {
        val jabbit = newJabbit {
            worker("never") { JabbitWorker { JobResult.success() } }
        }

        val request = oneTimeJob("never") {
            setConstraints(constraints { requiredNetworkType = NetworkType.METERED })
        }
        jabbit.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) { awaitState(jabbit, request.id, JobState.ENQUEUED) }
        }

        jabbit.cancelJob(request.id)
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) { awaitState(jabbit, request.id, JobState.CANCELLED) }
        }
    }

    @Test
    fun survivesAReloadOfTheSameQueue() = runTest {
        val storage = IndexedDbJabbitStorage(databaseName = "jabbit-test-${Random.nextInt()}")
        val first = newJabbit(storage) {
            worker("slow") { JabbitWorker { JobResult.retry() } }
        }

        val request = oneTimeJob("slow") {
            setBackoffCriteriaMillis(BackoffPolicy.LINEAR, 60_000)
        }
        first.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                awaitState(first, request.id, JobState.ENQUEUED) { it.runAttemptCount == 1 }
            }
        }

        val second = newJabbit(storage) {
            worker("slow") { JabbitWorker { JobResult.retry() } }
        }
        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                awaitState(second, request.id, JobState.ENQUEUED) { it.runAttemptCount == 1 }
            }
        }
    }

    private suspend fun awaitState(
        jabbit: Jabbit,
        id: JobId,
        state: JobState,
        predicate: (JobInfo) -> Boolean = { true }
    ) {
        while (true) {
            val info = jabbit.getJobInfo(id)
            if (info != null && info.state == state && predicate(info)) return
            kotlinx.coroutines.delay(50)
        }
    }

    private fun newJabbit(
        storage: JabbitStorage = IndexedDbJabbitStorage(
            databaseName = "jabbit-test-${Random.nextInt()}"
        ),
        configure: JabbitConfiguration.Builder.() -> Unit
    ): Jabbit = createJabbit(
        configuration = JabbitConfiguration.Builder().apply(configure).build(),
        options = JabbitBrowserOptions(
            storage = storage,
            backgroundSyncTag = null,
            coordinateTabs = false
        )
    )
}
