package tech.ryadom.jabbit

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.ryadom.jabbit.internal.JabbitClock
import tech.ryadom.jabbit.internal.JobEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JabbitListenerTest {

    private val recorder = RecordingListener()

    @Test
    fun reportsAJobFromEnqueueToSuccess() = runTest {
        val engine = engine { worker(OkJob) { JabbitWorker { JobResult.success(7) } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(OkJob)))
        settle()

        assertEquals(listOf("enqueued", "started", "succeeded"), recorder.events)
    }

    @Test
    fun reportsARetryAndHowFarAwayItIs() = runTest {
        val engine = engine {
            worker(FlakyJob) { JabbitWorker { JobResult.retry() } }
        }
        engine.start()
        engine.enqueue(
            listOf(oneTimeJob(FlakyJob) { setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds) })
        )
        settle(window = 1.seconds)

        assertEquals(listOf("enqueued", "started", "retry"), recorder.events)
        assertTrue(
            recorder.retryDelayMillis!! >= 10.seconds.inWholeMilliseconds,
            "the reported delay must match the backoff"
        )
    }

    @Test
    fun reportsWhyAJobFailed() = runTest {
        val engine = engine {
            worker(FailingJob) { JabbitWorker { JobResult.failure("the server said no") } }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(FailingJob)))
        settle()

        assertEquals(listOf("enqueued", "started", "failed"), recorder.events)
        assertEquals("the server said no", recorder.failureReason)
    }

    @Test
    fun handsOverWhatAWorkerThrew() = runTest {
        val engine = engine { worker(ThrowingJob) { JabbitWorker { error("boom") } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(ThrowingJob)))
        settle()

        assertEquals("boom", recorder.failure?.message)
    }

    @Test
    fun reportsCancellation() = runTest {
        val engine =
            engine {
                worker(SlowJob) {
                    JabbitWorker {
                        delay(1.hours)
                        JobResult.success()
                    }
                }
            }
        engine.start()

        val request = oneTimeJob(SlowJob)
        engine.enqueue(listOf(request))
        settle()
        engine.cancel(request.id)
        settle()

        assertEquals(listOf("enqueued", "started", "cancelled"), recorder.events)
    }

    @Test
    fun reportsAReplacedJobAsCancelled() = runTest {
        val engine =
            engine {
                worker(SlowJob) {
                    JabbitWorker {
                        delay(1.hours)
                        JobResult.success()
                    }
                }
            }
        engine.start()

        engine.enqueueUnique("sync", ExistingJobPolicy.KEEP, oneTimeJob(SlowJob))
        settle()
        engine.enqueueUnique("sync", ExistingJobPolicy.REPLACE, oneTimeJob(SlowJob))
        settle()

        assertEquals(
            listOf("enqueued", "started", "cancelled", "enqueued", "started"),
            recorder.events
        )
    }

    @Test
    fun keepsSchedulingWhenAListenerThrows() = runTest {
        val engine = engine(
            listener = object : JabbitListener {
                override fun onStarted(job: JobInfo) = error("listener is broken")
            }
        ) { worker(OkJob) { JabbitWorker { JobResult.success(7) } } }

        engine.start()
        engine.enqueue(listOf(oneTimeJob(OkJob)))
        settle()

        assertEquals(JobState.SUCCEEDED, engine.snapshot().single().state)
    }

    private fun TestScope.settle(window: Duration = 1.minutes) {
        advanceTimeBy(window)
        runCurrent()
    }

    private fun TestScope.engine(
        listener: JabbitListener = recorder,
        configure: JabbitScope.() -> Unit
    ): JobEngine = JobEngine(
        configuration = JabbitScope()
            .apply(configure)
            .apply { listener(listener) }
            .buildConfiguration(),
        storage = InMemoryJobRecordStorage(),
        deviceStateProvider = FakeDeviceStateProvider(),
        scope = backgroundScope,
        clock = JabbitClock { testScheduler.currentTime }
    )
}

private class RecordingListener : JabbitListener {

    val events = mutableListOf<String>()

    var retryDelayMillis: Long? = null

    var failureReason: String? = null

    var failure: Throwable? = null

    override fun onEnqueued(job: JobInfo) {
        events += "enqueued"
    }

    override fun onStarted(job: JobInfo) {
        events += "started"
    }

    override fun onSucceeded(job: JobInfo) {
        events += "succeeded"
    }

    override fun onFailed(job: JobInfo, error: Throwable?) {
        events += "failed"
        failureReason = job.failureReason
        failure = error
    }

    override fun onRetryScheduled(job: JobInfo, delayMillis: Long?) {
        events += "retry"
        retryDelayMillis = delayMillis
    }

    override fun onStopped(job: JobInfo) {
        events += "stopped"
    }

    override fun onCancelled(job: JobInfo) {
        events += "cancelled"
    }
}
