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

class BrowserJabbitTest {

    @Test
    fun runsAJobAndReportsItsOutcome() = runTest {
        val finished = CompletableDeferred<String>()
        val jabbit = newJabbit {
            worker(GreetJob) {
                JabbitWorker { job ->
                    val greeting = "hello ${job.input.name}"
                    finished.complete(greeting)
                    JobResult.success(greeting)
                }
            }
        }

        val request = oneTimeJob(GreetJob, Greeting("world"))
        jabbit.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                assertEquals("hello world", finished.await())
                awaitState(jabbit, request.id, JobState.SUCCEEDED)
            }
        }

        assertEquals("hello world", jabbit.getJobInfo(request.id)?.output(GreetJob))
    }

    @Test
    fun waitsForConstraintsItCannotSatisfy() = runTest {
        val jabbit = newJabbit { worker(NeverJob) { JabbitWorker { JobResult.success() } } }

        val request = oneTimeJob(NeverJob) {
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
        val databaseName = "jabbit-test-${Random.nextInt()}"
        val request = oneTimeJob(FlakyJob) {
            setBackoffCriteriaMillis(BackoffPolicy.LINEAR, 60_000)
        }

        val first = newJabbit(databaseName) {
            worker(FlakyJob) { JabbitWorker { JobResult.retry() } }
        }
        first.enqueue(request)

        withContext(Dispatchers.Default) {
            withTimeout(20.seconds) {
                awaitState(first, request.id, JobState.ENQUEUED) { it.runAttemptCount == 1 }
            }
        }

        val second = newJabbit(databaseName) {
            worker(FlakyJob) { JabbitWorker { JobResult.retry() } }
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
            delay(50)
        }
    }

    private fun newJabbit(
        databaseName: String = "jabbit-test-${Random.nextInt()}",
        configure: JabbitScope.() -> Unit
    ): Jabbit = jabbit {
        configure()
        browser {
            queueName = databaseName
            storage = IndexedDbJabbitStorage(databaseName = databaseName)
            backgroundSyncTag = null
            coordinateTabs = false
        }
    }
}
