package tech.ryadom.jabbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.ryadom.jabbit.internal.JabbitInitializationProvider
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkManagerJabbitTest {

    private lateinit var context: Context

    private val runs = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
        )
        Robolectric.setupContentProvider(JabbitInitializationProvider::class.java)
        runs.set(0)
    }

    @Test
    fun runsAJobAndKeepsItsOutput() {
        val jabbit = jabbit {
            worker(GreetJob) {
                JabbitWorker { job ->
                    runs.incrementAndGet()
                    JobResult.success("hello ${job.input.name}")
                }
            }
            android { }
        }

        val request = oneTimeJob(GreetJob, Greeting("android")) { addTag("group") }
        runBlocking { jabbit.enqueue(request) }

        val info = awaitJob(jabbit, request.id) { it.state == JobState.SUCCEEDED }

        assertEquals(1, runs.get())
        assertEquals("hello android", info.output(GreetJob))
        assertEquals("greet", info.typeName)
        assertEquals(setOf("group"), info.tags)
    }

    @Test
    fun waitsUntilConstraintsAreMet() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val request = oneTimeJob(OkJob) {
            setConstraints(constraints { requiredNetworkType = NetworkType.UNMETERED })
        }
        runBlocking { jabbit.enqueue(request) }

        assertEquals(JobState.ENQUEUED, runBlocking { jabbit.getJobInfo(request.id) }?.state)
        assertEquals(0, runs.get())

        testDriver().setAllConstraintsMet(UUID.fromString(request.id.value))

        awaitJob(jabbit, request.id) { it.state == JobState.SUCCEEDED }
        assertEquals(1, runs.get())
    }

    @Test
    fun waitsForTheInitialDelay() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val request = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }
        runBlocking { jabbit.enqueue(request) }

        assertEquals(JobState.ENQUEUED, runBlocking { jabbit.getJobInfo(request.id) }?.state)

        testDriver().setInitialDelayMet(UUID.fromString(request.id.value))

        awaitJob(jabbit, request.id) { it.state == JobState.SUCCEEDED }
    }

    @Test
    fun retriesAndCountsAttempts() {
        val jabbit = jabbit { worker(FlakyJob) { retryingWorker() } }

        val request = oneTimeJob(FlakyJob)
        runBlocking { jabbit.enqueue(request) }

        val info = awaitJob(jabbit, request.id) { it.runAttemptCount >= 1 }

        assertEquals(JobState.ENQUEUED, info.state)
        assertEquals(1, info.runAttemptCount)
        assertEquals(1, runs.get())
    }

    @Test
    fun givesUpWhenAttemptsAreCapped() {
        val jabbit = jabbit { worker(FlakyJob) { retryingWorker() } }

        val request = oneTimeJob(FlakyJob) { setMaxAttempts(1) }
        runBlocking { jabbit.enqueue(request) }

        val info = awaitJob(jabbit, request.id) { it.state == JobState.FAILED }

        assertEquals(1, runs.get())
        assertTrue(info.failureReason.orEmpty().contains("gave up"))
    }

    @Test
    fun reportsWhyAJobFailed() {
        val jabbit = jabbit {
            worker(FailingJob) { JabbitWorker { JobResult.failure("the server said no") } }
        }

        val request = oneTimeJob(FailingJob)
        runBlocking { jabbit.enqueue(request) }

        val info = awaitJob(jabbit, request.id) { it.state == JobState.FAILED }

        assertEquals("the server said no", info.failureReason)
    }

    @Test
    fun keepsTheJobAlreadyEnqueuedUnderAUniqueName() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val first = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }
        val second = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }

        runBlocking {
            jabbit.enqueueUnique("sync", ExistingJobPolicy.KEEP, first)
            jabbit.enqueueUnique("sync", ExistingJobPolicy.KEEP, second)
        }

        val infos = runBlocking { jabbit.getJobInfosForUniqueJob("sync") }

        assertEquals(1, infos.size)
        assertEquals(first.id, infos.single().id)
        assertEquals("sync", infos.single().uniqueName)
    }

    @Test
    fun replacesTheJobEnqueuedUnderAUniqueName() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val first = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }
        val second = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }

        runBlocking {
            jabbit.enqueueUnique("sync", ExistingJobPolicy.KEEP, first)
            jabbit.enqueueUnique("sync", ExistingJobPolicy.REPLACE, second)
        }

        assertNull(
            runBlocking { jabbit.getJobInfo(first.id) },
            "a replaced job stops being observable"
        )
        assertEquals(JobState.ENQUEUED, runBlocking { jabbit.getJobInfo(second.id) }?.state)
    }

    @Test
    fun cancelsJobsByTag() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val request = oneTimeJob(OkJob) {
            setInitialDelay(10.minutes)
            addTag("group")
        }
        runBlocking {
            jabbit.enqueue(request)
            jabbit.cancelJobsByTag("group")
        }

        awaitJob(jabbit, request.id) { it.state == JobState.CANCELLED }
        assertEquals(0, runs.get())
    }

    @Test
    fun repeatsAPeriodicJob() {
        val jabbit = jabbit { worker(OkJob) { succeedingWorker() } }

        val request = periodicJob(OkJob, repeatInterval = 15.minutes)
        runBlocking { jabbit.enqueue(request) }

        awaitJob(jabbit, request.id) { runs.get() == 1 }

        testDriver().setPeriodDelayMet(UUID.fromString(request.id.value))

        awaitJob(jabbit, request.id) { runs.get() == 2 }
        assertTrue(runBlocking { jabbit.getJobInfo(request.id) }?.state?.isFinished == false)
    }

    @Test
    fun reportsWhatHappensToAJob() {
        val events = CopyOnWriteArrayList<String>()

        val jabbit = jabbit {
            worker(OkJob) { succeedingWorker() }
            listener(
                object : JabbitListener {
                    override fun onEnqueued(job: JobInfo) {
                        events += "enqueued"
                    }

                    override fun onStarted(job: JobInfo) {
                        events += "started"
                    }

                    override fun onSucceeded(job: JobInfo) {
                        events += "succeeded"
                    }
                }
            )
        }

        val request = oneTimeJob(OkJob)
        runBlocking { jabbit.enqueue(request) }

        awaitJob(jabbit, request.id) { it.state == JobState.SUCCEEDED }

        assertEquals(listOf("enqueued", "started", "succeeded"), events.toList())
    }

    @Test
    fun reportsCancellation() {
        val cancelled = CopyOnWriteArrayList<String>()

        val jabbit = jabbit {
            worker(OkJob) { succeedingWorker() }
            listener(
                object : JabbitListener {
                    override fun onCancelled(job: JobInfo) {
                        cancelled += job.typeName
                    }
                }
            )
        }

        val request = oneTimeJob(OkJob) { setInitialDelay(10.minutes) }
        runBlocking {
            jabbit.enqueue(request)
            jabbit.cancelJob(request.id)
        }

        assertEquals(listOf("ok"), cancelled.toList())
    }

    private fun succeedingWorker(): JabbitWorker<Unit, Int> = JabbitWorker {
        JobResult.success(runs.incrementAndGet())
    }

    private fun retryingWorker(): JabbitWorker<Unit, Unit> = JabbitWorker {
        runs.incrementAndGet()
        JobResult.retry()
    }

    private fun testDriver() = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))

    private fun awaitJob(
        jabbit: Jabbit,
        id: JobId,
        timeoutMillis: Long = 5_000,
        predicate: (JobInfo) -> Boolean
    ): JobInfo {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: JobInfo? = null

        while (System.currentTimeMillis() < deadline) {
            last = runBlocking { jabbit.getJobInfo(id) }
            if (last != null && predicate(last)) return last
            Thread.sleep(25)
        }

        throw AssertionError("Job $id never matched, last seen: $last")
    }
}
