package tech.ryadom.jabbit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.ryadom.jabbit.internal.DeviceState
import tech.ryadom.jabbit.internal.DeviceStateProvider
import tech.ryadom.jabbit.internal.JobEngine
import tech.ryadom.jabbit.internal.JobRecordStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JobEngineTest {

    @Test
    fun runsAOneTimeJobAndKeepsItsOutput() = runTest {
        val engine = engine {
            worker(OkJob) { JabbitWorker { JobResult.success(42) } }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(OkJob)))
        settle()

        val record = engine.snapshot().single()
        assertEquals(JobState.SUCCEEDED, record.state)
        assertEquals("42", record.encodedOutput)
        assertEquals(0, record.runAttemptCount)
    }

    @Test
    fun honoursTheInitialDelay() = runTest {
        val engine = engine { worker(OkJob) { JabbitWorker { JobResult.success(1) } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(OkJob) { setInitialDelay(30.seconds) }))

        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(JobState.ENQUEUED, engine.snapshot().single().state)

        advanceTimeBy(25.seconds)
        runCurrent()
        assertEquals(JobState.SUCCEEDED, engine.snapshot().single().state)
    }

    @Test
    fun retriesWithBackoffAndCountsAttempts() = runTest {
        val attempts = mutableListOf<Int>()
        val engine = engine {
            worker(FlakyJob) {
                JabbitWorker { job ->
                    attempts += job.runAttemptCount
                    if (job.runAttemptCount < 2) JobResult.retry() else JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(
            listOf(
                oneTimeJob(FlakyJob) {
                    setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds)
                }
            )
        )

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(listOf(0), attempts)

        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(listOf(0, 1), attempts)

        advanceTimeBy(25.seconds)
        runCurrent()
        assertEquals(listOf(0, 1, 2), attempts)
        assertEquals(JobState.SUCCEEDED, engine.snapshot().single().state)
    }

    @Test
    fun givesUpAfterTheAllowedNumberOfAttempts() = runTest {
        var runs = 0
        val engine = engine {
            worker(FlakyJob) {
                JabbitWorker {
                    runs++
                    JobResult.retry()
                }
            }
        }
        engine.start()
        engine.enqueue(
            listOf(
                oneTimeJob(FlakyJob) {
                    setMaxAttempts(3)
                    setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds)
                }
            )
        )

        advanceTimeBy(2.minutes)
        runCurrent()

        assertEquals(3, runs)
        assertEquals(JobState.FAILED, engine.snapshot().single().state)
    }

    @Test
    fun retriesWithoutALimitByDefault() = runTest {
        var runs = 0
        val engine = engine {
            worker(FlakyJob) {
                JabbitWorker {
                    runs++
                    JobResult.retry()
                }
            }
        }
        engine.start()
        engine.enqueue(
            listOf(oneTimeJob(FlakyJob) { setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds) })
        )

        advanceTimeBy(2.minutes)
        runCurrent()

        assertTrue(runs > 3, "expected more than three attempts, got $runs")
        assertEquals(JobState.ENQUEUED, engine.snapshot().single().state)
    }

    @Test
    fun failsPermanentlyOnFailureAndOnThrow() = runTest {
        val engine = engine {
            worker(FailingJob) {
                JabbitWorker { JobResult.failure("nope") }
            }
            worker(ThrowingJob) { JabbitWorker { error("boom") } }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(FailingJob), oneTimeJob(ThrowingJob)))
        settle()

        val states = engine.snapshot().associate { it.typeName to it.state }
        assertEquals(JobState.FAILED, states["failing"])
        assertEquals(JobState.FAILED, states["throwing"])
        assertEquals(
            "nope",
            engine.snapshot().first { it.typeName == "failing" }.failureReason
        )
    }

    @Test
    fun waitsUntilConstraintsAreSatisfied() = runTest {
        val device = FakeDeviceStateProvider(DeviceState(networkConnected = false))
        val engine = engine(deviceState = device) {
            worker(UploadJob) { JabbitWorker { JobResult.success() } }
        }
        engine.start()
        engine.enqueue(
            listOf(
                oneTimeJob(UploadJob) {
                    setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
                }
            )
        )

        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(JobState.ENQUEUED, engine.snapshot().single().state)

        device.update { copy(networkConnected = true) }
        settle()
        assertEquals(JobState.SUCCEEDED, engine.snapshot().single().state)
    }

    @Test
    fun cancelStopsARunningJob() = runTest {
        val started = CompletableDeferred<Unit>()
        var finished = false
        val engine = engine {
            worker(SlowJob) {
                JabbitWorker {
                    started.complete(Unit)
                    delay(1.hours)
                    finished = true
                    JobResult.success()
                }
            }
        }
        engine.start()
        val request = oneTimeJob(SlowJob)
        engine.enqueue(listOf(request))

        advanceTimeBy(1.seconds)
        runCurrent()
        assertTrue(started.isCompleted)
        assertEquals(JobState.RUNNING, engine.snapshot().single().state)

        engine.cancel(request.id)
        advanceTimeBy(2.hours)
        runCurrent()

        assertEquals(JobState.CANCELLED, engine.snapshot().single().state)
        assertEquals(false, finished)
    }

    @Test
    fun cancelsByTagAndByUniqueName() = runTest {
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
        engine.enqueue(listOf(oneTimeJob(SlowJob) { addTag("group") }))
        engine.enqueueUnique("named", ExistingJobPolicy.KEEP, oneTimeJob(SlowJob))
        settle()

        engine.cancelByTag("group")
        engine.cancelUnique("named")
        runCurrent()

        assertTrue(engine.snapshot().all { it.state == JobState.CANCELLED })
    }

    @Test
    fun keepsOrReplacesUniqueJobs() = runTest {
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

        val first = oneTimeJob(SlowJob)
        engine.enqueueUnique("sync", ExistingJobPolicy.KEEP, first)
        engine.enqueueUnique("sync", ExistingJobPolicy.KEEP, oneTimeJob(SlowJob))
        settle()
        assertEquals(listOf(first.id.value), engine.snapshot().map { it.id })

        val replacement = oneTimeJob(SlowJob)
        engine.enqueueUnique("sync", ExistingJobPolicy.REPLACE, replacement)
        settle()

        val states = engine.snapshot().associate { it.id to it.state }
        assertNull(states[first.id.value], "a replaced job stops being observable")
        assertEquals(JobState.RUNNING, states[replacement.id.value])
    }

    @Test
    fun updatesAUniquePeriodicJobWithoutRestartingItsPeriod() = runTest {
        val engine = engine { worker(SyncJob) { JabbitWorker { JobResult.success() } } }
        engine.start()

        val original = periodicJob(SyncJob, Greeting("first"), repeatInterval = 1.hours) {
            setInitialDelay(30.minutes)
        }
        engine.enqueueUniquePeriodic("sync", ExistingPeriodicJobPolicy.KEEP, original)
        settle()
        val scheduledAt = engine.snapshot().single().earliestRunAtMillis

        engine.enqueueUniquePeriodic(
            uniqueName = "sync",
            policy = ExistingPeriodicJobPolicy.UPDATE,
            request = periodicJob(SyncJob, Greeting("second"), repeatInterval = 2.hours)
        )
        settle()

        val record = engine.snapshot().single()
        assertEquals(original.id.value, record.id)
        assertEquals(scheduledAt, record.earliestRunAtMillis)
        assertEquals("{\"name\":\"second\"}", record.encodedInput)
        assertEquals(2.hours.inWholeMilliseconds, record.repeatIntervalMillis)
    }

    @Test
    fun reschedulesPeriodicJobsAfterEverySuccess() = runTest {
        var runs = 0
        val engine = engine {
            worker(BeatJob) {
                JabbitWorker {
                    runs++
                    JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(periodicJob(BeatJob, repeatInterval = 15.minutes)))

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, runs)
        assertEquals(JobState.ENQUEUED, engine.snapshot().single().state)

        advanceTimeBy(16.minutes)
        runCurrent()
        assertEquals(2, runs)

        advanceTimeBy(16.minutes)
        runCurrent()
        assertEquals(3, runs)
    }

    @Test
    fun stopsRepeatingWhenAPeriodicJobFails() = runTest {
        var runs = 0
        val engine = engine {
            worker(BeatJob) {
                JabbitWorker {
                    runs++
                    JobResult.failure()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(periodicJob(BeatJob, repeatInterval = 15.minutes)))

        advanceTimeBy(2.hours)
        runCurrent()

        assertEquals(1, runs)
        assertEquals(JobState.FAILED, engine.snapshot().single().state)
    }

    @Test
    fun limitsHowManyJobsRunAtOnce() = runTest {
        var peak = 0
        var active = 0
        val engine = engine(concurrency = 2) {
            worker(SlowJob) {
                JabbitWorker {
                    active++
                    peak = maxOf(peak, active)
                    delay(10.seconds)
                    active--
                    JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(List(5) { oneTimeJob(SlowJob) })

        advanceTimeBy(2.minutes)
        runCurrent()

        assertEquals(2, peak)
        assertTrue(engine.snapshot().all { it.state == JobState.SUCCEEDED })
    }

    @Test
    fun publishesProgressWhileRunning() = runTest {
        val engine = engine {
            worker(ReportingJob) {
                JabbitWorker { job ->
                    job.setProgress(JobProgress(fraction = 0.5f))
                    delay(10.seconds)
                    JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(ReportingJob)))

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(0.5f, engine.snapshot().single().progress?.fraction)

        advanceTimeBy(20.seconds)
        runCurrent()
        assertNull(engine.snapshot().single().progress)
    }

    @Test
    fun persistsJobsAndRecoversInterruptedOnes() = runTest {
        val storage = InMemoryJobRecordStorage()
        val first = engine(storage = storage) {
            worker(SlowJob) {
                JabbitWorker {
                    delay(1.hours)
                    JobResult.success()
                }
            }
        }
        first.start()
        first.enqueue(listOf(oneTimeJob(SlowJob)))
        settle()
        assertEquals(JobState.RUNNING, storage.load().single().state)

        val restarted = engine(storage = storage) {
            worker(SlowJob) { JabbitWorker { JobResult.success() } }
        }
        restarted.start()
        settle()

        val record = restarted.snapshot().single()
        assertEquals(JobState.SUCCEEDED, record.state)
        assertEquals(1, record.runAttemptCount)
    }

    @Test
    fun rejectsJobTypesThatWereNeverRegistered() = runTest {
        val engine = engine { worker(OkJob) { JabbitWorker { JobResult.success(1) } } }

        assertFailsWith<IllegalArgumentException> {
            engine.enqueue(listOf(oneTimeJob(GreetJob, Greeting("world"))))
        }
    }

    @Test
    fun prunesFinishedJobs() = runTest {
        val engine = engine { worker(OkJob) { JabbitWorker { JobResult.success(1) } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob(OkJob)))
        settle()
        assertEquals(1, engine.snapshot().size)

        engine.prune()
        assertTrue(engine.snapshot().isEmpty())
        assertNull(engine.snapshot().firstOrNull())
    }

    private fun TestScope.settle() {
        advanceTimeBy(1.seconds)
        runCurrent()
    }

    private fun TestScope.engine(
        deviceState: DeviceStateProvider = FakeDeviceStateProvider(),
        storage: JobRecordStorage = InMemoryJobRecordStorage(),
        concurrency: Int = 4,
        configure: JabbitScope.() -> Unit
    ): JobEngine = JobEngine(
        configuration = JabbitScope()
            .apply(configure)
            .apply { maxConcurrentJobs(concurrency) }
            .buildConfiguration(),
        storage = storage,
        deviceStateProvider = deviceState,
        scope = backgroundScope,
        clock = { testScheduler.currentTime }
    )
}
