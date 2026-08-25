package tech.ryadom.jabbit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopJabbitTest {

    @Test
    fun runsAJobAndReportsItsOutcome() = runTest {
        val finished = CompletableDeferred<String>()
        val jabbit = newJabbit(createTempDirectory("jabbit")) {
            worker("greet") {
                JabbitWorker { job ->
                    val greeting = "hello ${job.inputData.getString("name")}"
                    finished.complete(greeting)
                    JobResult.success(jobDataOf("greeting" to greeting))
                }
            }
        }

        try {
            val request = oneTimeJob("greet") { setInputData(jobDataOf("name" to "desktop")) }
            jabbit.enqueue(request)

            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    assertEquals("hello desktop", finished.await())
                    awaitState(jabbit, request.id, JobState.SUCCEEDED)
                }
            }

            assertEquals(
                "hello desktop",
                jabbit.getJobInfo(request.id)?.outputData?.getString("greeting")
            )
        } finally {
            jabbit.close()
        }
    }

    @Test
    fun picksUpAnUnfinishedQueueOnTheNextStart() = runTest {
        val directory = createTempDirectory("jabbit")

        val first = newJabbit(directory) {
            worker("flaky") { JabbitWorker { JobResult.retry() } }
        }

        val request = oneTimeJob("flaky") {
            setBackoffCriteriaMillis(BackoffPolicy.LINEAR, 60_000)
        }

        try {
            first.enqueue(request)
            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    awaitState(first, request.id, JobState.ENQUEUED) { it.runAttemptCount == 1 }
                }
            }
        } finally {
            first.close()
        }

        val second = newJabbit(directory) {
            worker("flaky") { JabbitWorker { JobResult.retry() } }
        }

        try {
            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    awaitState(second, request.id, JobState.ENQUEUED) {
                        it.runAttemptCount == 1
                    }
                }
            }
        } finally {
            second.close()
        }
    }

    @Test
    fun waitsForConstraintsItCannotSatisfy() = runTest {
        val jabbit = newJabbit(createTempDirectory("jabbit")) {
            worker("never") { JabbitWorker { JobResult.success() } }
        }

        try {
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
        } finally {
            jabbit.close()
        }
    }

    @Test
    fun refusesToShareAStorageDirectoryWithAnotherInstance() {
        val directory = createTempDirectory("jabbit")
        val first = newJabbit(directory) { worker("noop") { JabbitWorker { JobResult.success() } } }

        try {
            val failure = assertFailsWith<IllegalStateException> {
                newJabbit(directory) { worker("noop") { JabbitWorker { JobResult.success() } } }
            }
            assertTrue(failure.message.orEmpty().contains("Another process"))
        } finally {
            first.close()
        }

        newJabbit(directory) { worker("noop") { JabbitWorker { JobResult.success() } } }.close()
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
        directory: Path,
        configure: JabbitConfiguration.Builder.() -> Unit
    ): DesktopJabbit = createJabbit(
        configuration = JabbitConfiguration.Builder().apply(configure).build(),
        options = JabbitDesktopOptions(
            applicationName = "jabbit-test",
            storageDirectory = directory
        )
    )
}
