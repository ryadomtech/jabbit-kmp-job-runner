package tech.ryadom.jabbit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        val jabbit = newJabbit {
            worker(GreetJob) {
                JabbitWorker { job ->
                    val greeting = "hello ${job.input.name}"
                    finished.complete(greeting)
                    JobResult.success(greeting)
                }
            }
        }

        try {
            val request = oneTimeJob(GreetJob, Greeting("desktop"))
            jabbit.enqueue(request)

            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    assertEquals("hello desktop", finished.await())
                    awaitState(jabbit, request.id, JobState.SUCCEEDED)
                }
            }

            assertEquals("hello desktop", jabbit.getJobInfo(request.id)?.output(GreetJob))
        } finally {
            jabbit.close()
        }
    }

    @Test
    fun picksUpAnUnfinishedQueueOnTheNextStart() = runTest {
        val directory = createTempDirectory("jabbit").toString()
        val request = oneTimeJob(FlakyJob) {
            setBackoffCriteriaMillis(BackoffPolicy.LINEAR, 60_000)
        }

        val first = newJabbit(directory) {
            worker(FlakyJob) { JabbitWorker { JobResult.retry() } }
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
            worker(FlakyJob) { JabbitWorker { JobResult.retry() } }
        }

        try {
            withContext(Dispatchers.Default) {
                withTimeout(20.seconds) {
                    awaitState(second, request.id, JobState.ENQUEUED) { it.runAttemptCount == 1 }
                }
            }
        } finally {
            second.close()
        }
    }

    @Test
    fun waitsForConstraintsItCannotSatisfy() = runTest {
        val jabbit = newJabbit {
            worker(NeverJob) { JabbitWorker { JobResult.success() } }
        }

        try {
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
        } finally {
            jabbit.close()
        }
    }

    @Test
    fun refusesToShareAStorageDirectoryWithAnotherInstance() {
        val directory = createTempDirectory("jabbit").toString()
        val first = createJabbit(directory, singleInstance = true)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                createJabbit(directory, singleInstance = true)
            }
            assertTrue(failure.message.orEmpty().contains("Another process"))
        } finally {
            first.close()
        }

        createJabbit(directory, singleInstance = true).close()
    }

    @Test
    fun refusesToBuildOnTheDesktopWithoutAnApplicationName() {
        val failure = assertFailsWith<IllegalStateException> {
            jabbit { worker(OkJob) { JabbitWorker { JobResult.success(1) } } }
        }

        assertTrue(failure.message.orEmpty().contains("applicationName"))
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
        directory: String = createTempDirectory("jabbit").toString(),
        configure: JabbitScope.() -> Unit
    ): DesktopJabbit = jabbit {
        configure()
        desktop {
            applicationName = "jabbit-test"
            storageDirectory = directory
            singleInstanceLock = false
            readPowerSource = false
        }
    } as DesktopJabbit

    private fun createJabbit(directory: String, singleInstance: Boolean): DesktopJabbit = jabbit {
        worker(OkJob) { JabbitWorker { JobResult.success(1) } }
        desktop {
            applicationName = "jabbit-test"
            storageDirectory = directory
            singleInstanceLock = singleInstance
            readPowerSource = false
        }
    } as DesktopJabbit
}
