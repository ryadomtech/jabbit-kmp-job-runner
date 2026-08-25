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
            worker("ok") { JabbitWorker { JobResult.success(jobDataOf("answer" to 42)) } }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("ok")))
        settle()

        val record = engine.snapshot().single()
        assertEquals(JobState.SUCCEEDED, record.state)
        assertEquals(42, record.outputData.getInt("answer"))
        assertEquals(0, record.runAttemptCount)
    }

    @Test
    fun honoursTheInitialDelay() = runTest {
        val engine = engine { worker("ok") { JabbitWorker { JobResult.success() } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("ok") { setInitialDelay(30.seconds) }))

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
            worker("flaky") {
                JabbitWorker { job ->
                    attempts += job.runAttemptCount
                    if (job.runAttemptCount < 2) JobResult.retry() else JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(
            listOf(
                oneTimeJob("flaky") {
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
    fun failsPermanentlyOnFailureAndOnThrow() = runTest {
        val engine = engine {
            worker("failing") {
                JabbitWorker { JobResult.failure(jobDataOf("reason" to "nope")) }
            }
            worker("throwing") { JabbitWorker { error("boom") } }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("failing"), oneTimeJob("throwing")))
        settle()

        val states = engine.snapshot().associate { it.workerName to it.state }
        assertEquals(JobState.FAILED, states["failing"])
        assertEquals(JobState.FAILED, states["throwing"])
        assertEquals(
            "nope",
            engine.snapshot()
                .first { it.workerName == "failing" }
                .outputData
                .getString("reason")
        )
    }

    @Test
    fun waitsUntilConstraintsAreSatisfied() = runTest {
        val device = FakeDeviceStateProvider(DeviceState(networkConnected = false))
        val engine = engine(deviceState = device) {
            worker("upload") { JabbitWorker { JobResult.success() } }
        }
        engine.start()
        engine.enqueue(
            listOf(
                oneTimeJob("upload") {
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
            worker("slow") {
                JabbitWorker {
                    started.complete(Unit)
                    delay(1.hours)
                    finished = true
                    JobResult.success()
                }
            }
        }
        engine.start()
        val request = oneTimeJob("slow")
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
                worker("slow") {
                    JabbitWorker {
                        delay(1.hours)
                        JobResult.success()
                    }
                }
            }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("slow") { addTag("group") }))
        engine.enqueueUnique("named", ExistingJobPolicy.KEEP, oneTimeJob("slow"))
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
                worker("slow") {
                    JabbitWorker {
                        delay(1.hours)
                        JobResult.success()
                    }
                }
            }
        engine.start()

        val first = oneTimeJob("slow")
        engine.enqueueUnique("sync", ExistingJobPolicy.KEEP, first)
        engine.enqueueUnique("sync", ExistingJobPolicy.KEEP, oneTimeJob("slow"))
        settle()
        assertEquals(listOf(first.id.value), engine.snapshot().map { it.id })

        val replacement = oneTimeJob("slow")
        engine.enqueueUnique("sync", ExistingJobPolicy.REPLACE, replacement)
        settle()

        val states = engine.snapshot().associate { it.id to it.state }
        assertEquals(JobState.CANCELLED, states[first.id.value])
        assertEquals(JobState.RUNNING, states[replacement.id.value])
    }

    @Test
    fun updatesAUniquePeriodicJobWithoutRestartingItsPeriod() = runTest {
        val engine = engine { worker("sync") { JabbitWorker { JobResult.success() } } }
        engine.start()

        val original = periodicJob("sync", repeatInterval = 1.hours) {
            setInitialDelay(30.minutes)
        }
        engine.enqueueUniquePeriodic("sync", ExistingPeriodicJobPolicy.KEEP, original)
        settle()
        val scheduledAt = engine.snapshot().single().earliestRunAtMillis

        engine.enqueueUniquePeriodic(
            uniqueName = "sync",
            policy = ExistingPeriodicJobPolicy.UPDATE,
            request = periodicJob("sync", repeatInterval = 2.hours) {
                setInputData(jobDataOf("updated" to true))
            }
        )
        settle()

        val record = engine.snapshot().single()
        assertEquals(original.id.value, record.id)
        assertEquals(scheduledAt, record.earliestRunAtMillis)
        assertEquals(true, record.inputData.getBoolean("updated"))
        assertEquals(2.hours.inWholeMilliseconds, record.repeatIntervalMillis)
    }

    @Test
    fun reschedulesPeriodicJobsAfterEverySuccess() = runTest {
        var runs = 0
        val engine = engine {
            worker("beat") {
                JabbitWorker {
                    runs++
                    JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(periodicJob("beat", repeatInterval = 15.minutes)))

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
            worker("beat") {
                JabbitWorker {
                    runs++
                    JobResult.failure()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(periodicJob("beat", repeatInterval = 15.minutes)))

        advanceTimeBy(2.hours)
        runCurrent()

        assertEquals(1, runs)
        assertEquals(JobState.FAILED, engine.snapshot().single().state)
    }

    @Test
    fun limitsHowManyJobsRunAtOnce() = runTest {
        var peak = 0
        var active = 0
        val engine = engine(maxConcurrentJobs = 2) {
            worker("slow") {
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
        engine.enqueue(List(5) { oneTimeJob("slow") })

        advanceTimeBy(2.minutes)
        runCurrent()

        assertEquals(2, peak)
        assertTrue(engine.snapshot().all { it.state == JobState.SUCCEEDED })
    }

    @Test
    fun publishesProgressWhileRunning() = runTest {
        val engine = engine {
            worker("reporting") {
                JabbitWorker { job ->
                    job.setProgress(jobDataOf("percent" to 50))
                    delay(10.seconds)
                    JobResult.success()
                }
            }
        }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("reporting")))

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(50, engine.snapshot().single().progress.getInt("percent"))

        advanceTimeBy(20.seconds)
        runCurrent()
        assertTrue(engine.snapshot().single().progress.isEmpty())
    }

    @Test
    fun persistsJobsAndRecoversInterruptedOnes() = runTest {
        val storage = InMemoryJobRecordStorage()
        val first = engine(storage = storage) {
            worker("slow") {
                JabbitWorker {
                    delay(1.hours)
                    JobResult.success()
                }
            }
        }
        first.start()
        first.enqueue(listOf(oneTimeJob("slow")))
        settle()
        assertEquals(JobState.RUNNING, storage.load().single().state)

        val restarted = engine(storage = storage) {
            worker("slow") { JabbitWorker { JobResult.success() } }
        }
        restarted.start()
        settle()

        val record = restarted.snapshot().single()
        assertEquals(JobState.SUCCEEDED, record.state)
        assertEquals(1, record.runAttemptCount)
    }

    @Test
    fun failsJobsWhoseWorkerCannotBeCreated() = runTest {
        val engine = engine { workerFactory { null } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("missing")))
        settle()

        assertEquals(JobState.FAILED, engine.snapshot().single().state)
    }

    @Test
    fun rejectsUnregisteredWorkersUpFront() = runTest {
        val engine = engine { worker("known") { JabbitWorker { JobResult.success() } } }

        assertFailsWith<IllegalArgumentException> {
            engine.enqueue(listOf(oneTimeJob("unknown")))
        }
    }

    @Test
    fun prunesFinishedJobs() = runTest {
        val engine = engine { worker("ok") { JabbitWorker { JobResult.success() } } }
        engine.start()
        engine.enqueue(listOf(oneTimeJob("ok")))
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
        maxConcurrentJobs: Int = 4,
        configure: JabbitConfiguration.Builder.() -> Unit
    ): JobEngine = JobEngine(
        configuration = JabbitConfiguration.Builder()
            .apply(configure)
            .maxConcurrentJobs(maxConcurrentJobs)
            .build(),
        storage = storage,
        deviceStateProvider = deviceState,
        scope = backgroundScope,
        clock = { testScheduler.currentTime }
    )
}

private class FakeDeviceStateProvider(initial: DeviceState = DeviceState()) : DeviceStateProvider {

    private val state = MutableStateFlow(initial)

    override val changes: Flow<DeviceState> = state

    override suspend fun current(): DeviceState = state.value

    fun update(block: DeviceState.() -> DeviceState) {
        state.value = state.value.block()
    }
}
