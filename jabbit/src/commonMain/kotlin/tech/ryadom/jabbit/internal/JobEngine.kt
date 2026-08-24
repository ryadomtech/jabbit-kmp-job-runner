package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.JabbitConfiguration
import tech.ryadom.jabbit.JobData
import tech.ryadom.jabbit.JobExecution
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.JobResult
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.NetworkType
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import tech.ryadom.jabbit.debug
import tech.ryadom.jabbit.error
import tech.ryadom.jabbit.requireKnownWorker

private const val CONCURRENCY_POLL_MILLIS = 500L

internal class JobEngine(
    private val configuration: JabbitConfiguration,
    private val storage: JobRecordStorage,
    private val deviceStateProvider: DeviceStateProvider,
    private val scope: CoroutineScope,
    private val clock: JabbitClock = SystemClock,
    private val wakeUpPlanner: WakeUpPlanner = WakeUpPlanner { }
) {

    private val logger = configuration.logger
    private val mutex = Mutex()
    private val running = mutableMapOf<String, Job>()
    private val wakeUpSignal = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow<List<JobRecord>>(emptyList())

    private val initialization = scope.async(start = CoroutineStart.LAZY) {
        val now = clock.nowMillis()
        val restored = storage.load().map { it.recovered(now) }
        mutex.withLock {
            val known = state.value.associateBy(JobRecord::id)
            state.value = restored.filterNot { it.id in known } + state.value
            persistLocked()
        }
    }

    val records: StateFlow<List<JobRecord>> = state.asStateFlow()

    fun start() {
        scope.launch {
            initialization.await()
            while (isActive) {
                val waitMillis = tick()
                if (waitMillis == Long.MAX_VALUE) {
                    wakeUpSignal.receive()
                } else {
                    withTimeoutOrNull(waitMillis) { wakeUpSignal.receive() }
                }
            }
        }
        scope.launch {
            initialization.await()
            deviceStateProvider.changes.collect { wakeUp() }
        }
    }

    fun wakeUp() {
        wakeUpSignal.trySend(Unit)
    }

    suspend fun enqueue(requests: List<JobRequest>) {
        val now = clock.nowMillis()
        enqueueRecords(requests.map { it.toRecord(uniqueName = null, nowMillis = now) })
    }

    suspend fun enqueueRecords(records: List<JobRecord>) {
        records.forEach { configuration.requireKnownWorker(it.workerName) }
        initialization.await()
        mutex.withLock {
            records.forEach { putLocked(it) }
            persistLocked()
        }
        wakeUp()
    }

    suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    ) {
        enqueueUniqueRecord(
            uniqueName = uniqueName,
            policy = policy,
            record = request.toRecord(uniqueName = uniqueName, nowMillis = clock.nowMillis())
        )
    }

    suspend fun enqueueUniqueRecord(
        uniqueName: String,
        policy: ExistingJobPolicy,
        record: JobRecord
    ) {
        configuration.requireKnownWorker(record.workerName)
        initialization.await()
        val now = clock.nowMillis()
        mutex.withLock {
            val existing = unfinishedUniqueLocked(uniqueName)
            when {
                existing == null -> Unit
                policy == ExistingJobPolicy.KEEP -> {
                    logger.debug("Unique job '$uniqueName' already exists, keeping it")
                    return@withLock
                }

                else -> cancelLocked(existing.id, now)
            }
            putLocked(record)
            persistLocked()
        }
        wakeUp()
    }

    suspend fun enqueueUniquePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        request: PeriodicJobRequest
    ) {
        enqueueUniquePeriodicRecord(
            uniqueName = uniqueName,
            policy = policy,
            record = request.toRecord(uniqueName = uniqueName, nowMillis = clock.nowMillis())
        )
    }

    suspend fun enqueueUniquePeriodicRecord(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        record: JobRecord
    ) {
        configuration.requireKnownWorker(record.workerName)
        initialization.await()
        val now = clock.nowMillis()
        mutex.withLock {
            val existing = unfinishedUniqueLocked(uniqueName)
            when {
                existing == null -> Unit

                policy == ExistingPeriodicJobPolicy.KEEP -> {
                    logger.debug("Unique periodic job '$uniqueName' already exists, keeping it")
                    return@withLock
                }

                policy == ExistingPeriodicJobPolicy.UPDATE -> {
                    putLocked(existing.withConfigurationOf(record))
                    persistLocked()
                    return@withLock
                }

                else -> cancelLocked(existing.id, now)
            }
            putLocked(record)
            persistLocked()
        }
        wakeUp()
    }

    suspend fun cancel(id: JobId) = cancelMatching { it.id == id.value }

    suspend fun cancelByTag(tag: String) = cancelMatching { tag in it.tags }

    suspend fun cancelUnique(uniqueName: String) = cancelMatching { it.uniqueName == uniqueName }

    suspend fun cancelAll() = cancelMatching { true }

    suspend fun prune() {
        initialization.await()
        mutex.withLock {
            val survivors = state.value.filterNot { it.state.isFinished }
            if (survivors.size != state.value.size) {
                state.value = survivors
                persistLocked()
            }
        }
    }

    suspend fun reloadFromStorage() {
        initialization.await()
        mutex.withLock {
            if (running.isNotEmpty()) return@withLock
            val now = clock.nowMillis()
            state.value = storage.load().map { it.recovered(now) }
        }
        wakeUp()
    }

    suspend fun snapshot(): List<JobRecord> {
        initialization.await()
        return state.value
    }

    suspend fun runUntilIdle() {
        initialization.await()
        while (true) {
            tick()
            val active = mutex.withLock { running.values.toList() }
            if (active.isEmpty()) return
            active.joinAll()
        }
    }

    private suspend fun cancelMatching(predicate: (JobRecord) -> Boolean) {
        initialization.await()
        val now = clock.nowMillis()
        mutex.withLock {
            val targets = state.value.filter { !it.state.isFinished && predicate(it) }
            if (targets.isEmpty()) return@withLock
            targets.forEach { cancelLocked(it.id, now) }
            persistLocked()
        }
        wakeUp()
    }

    private suspend fun tick(): Long {
        val now = clock.nowMillis()
        val deviceState = deviceStateProvider.current()
        var nextWakeAt = Long.MAX_VALUE

        mutex.withLock {
            for (record in state.value) {
                if (record.state != JobState.ENQUEUED || record.id in running) continue

                if (record.earliestRunAtMillis > now) {
                    nextWakeAt = minOf(nextWakeAt, record.earliestRunAtMillis)
                    continue
                }

                if (!record.constraints.isSatisfiedBy(deviceState)) continue

                if (running.size >= configuration.maxConcurrentJobs) {
                    nextWakeAt = minOf(nextWakeAt, now + CONCURRENCY_POLL_MILLIS)
                    break
                }

                startLocked(record, now)
            }

            pruneLocked(now)
            planWakeUpLocked(now)
        }

        return if (nextWakeAt == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            (nextWakeAt - now).coerceAtLeast(1L)
        }
    }

    private suspend fun startLocked(record: JobRecord, nowMillis: Long) {
        val worker = configuration.workerFactory.createWorker(record.workerName)
        if (worker == null) {
            logger.error("No worker registered for '${record.workerName}', failing job ${record.id}")
            putLocked(
                record.copy(
                    state = JobState.FAILED,
                    finishedAtMillis = nowMillis,
                    progress = JobData.EMPTY
                )
            )
            persistLocked()
            return
        }

        putLocked(record.copy(state = JobState.RUNNING))
        persistLocked()

        val execution = JobExecution(
            id = JobId(record.id),
            workerName = record.workerName,
            inputData = record.inputData,
            tags = record.tags,
            runAttemptCount = record.runAttemptCount,
            progressReporter = { data -> publishProgress(record.id, data) }
        )

        val job = scope.launch {
            val outcome = try {
                Outcome.Finished(worker.doWork(execution))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger.error("Worker '${record.workerName}' threw, failing job ${record.id}", error)
                Outcome.Finished(JobResult.Failure())
            }
            withContext(NonCancellable) { complete(record.id, outcome) }
        }

        job.invokeOnCompletion { cause ->
            if (cause != null) {
                scope.launch {
                    withContext(NonCancellable) { complete(record.id, Outcome.Interrupted) }
                }
            }
        }

        running[record.id] = job
    }

    private suspend fun complete(id: String, outcome: Outcome) {
        val now = clock.nowMillis()
        mutex.withLock {
            running.remove(id)
            val record = state.value.firstOrNull { it.id == id } ?: return@withLock
            if (record.state != JobState.RUNNING) return@withLock

            val updated = when (outcome) {
                Outcome.Interrupted -> record.retrying(now, applyBackoff = false)
                is Outcome.Finished -> when (val result = outcome.result) {
                    is JobResult.Success -> record.succeeded(now, result.outputData)
                    is JobResult.Failure -> record.copy(
                        state = JobState.FAILED,
                        finishedAtMillis = now,
                        outputData = result.outputData,
                        progress = JobData.EMPTY
                    )

                    JobResult.Retry -> record.retrying(now, applyBackoff = true)
                }
            }

            putLocked(updated)
            persistLocked()
        }
        wakeUp()
    }

    private suspend fun publishProgress(id: String, data: JobData) {
        mutex.withLock {
            val record = state.value.firstOrNull { it.id == id } ?: return@withLock
            if (record.state != JobState.RUNNING) return@withLock
            putLocked(record.copy(progress = data))
            persistLocked()
        }
    }

    private fun JobRecord.succeeded(nowMillis: Long, outputData: JobData): JobRecord = when {
        isPeriodic -> nextPeriod(nowMillis).copy(outputData = outputData)
        else -> copy(
            state = JobState.SUCCEEDED,
            finishedAtMillis = nowMillis,
            outputData = outputData,
            progress = JobData.EMPTY
        )
    }

    private fun JobRecord.retrying(nowMillis: Long, applyBackoff: Boolean): JobRecord {
        val attempt = runAttemptCount + 1
        val delay = if (applyBackoff) {
            backoffDelayMillis(backoffPolicy, backoffDelayMillis, attempt)
        } else {
            0L
        }
        return copy(
            state = JobState.ENQUEUED,
            runAttemptCount = attempt,
            earliestRunAtMillis = nowMillis + delay,
            progress = JobData.EMPTY
        )
    }

    private fun cancelLocked(id: String, nowMillis: Long) {
        running.remove(id)?.cancel()
        val record = state.value.firstOrNull { it.id == id } ?: return
        if (record.state.isFinished) return
        putLocked(
            record.copy(
                state = JobState.CANCELLED,
                finishedAtMillis = nowMillis,
                progress = JobData.EMPTY
            )
        )
    }

    private fun unfinishedUniqueLocked(uniqueName: String): JobRecord? =
        state.value.firstOrNull { it.uniqueName == uniqueName && !it.state.isFinished }

    private fun putLocked(record: JobRecord) {
        val current = state.value
        val index = current.indexOfFirst { it.id == record.id }
        state.value = if (index < 0) current + record else current.toMutableList().also {
            it[index] = record
        }
    }

    private fun pruneLocked(nowMillis: Long) {
        val retention = configuration.finishedJobRetention.inWholeMilliseconds
        val survivors = state.value.filterNot { record ->
            val finishedAt = record.finishedAtMillis ?: return@filterNot false
            record.state.isFinished && finishedAt + retention <= nowMillis
        }
        if (survivors.size != state.value.size) {
            state.value = survivors
        }
    }

    private fun planWakeUpLocked(nowMillis: Long) {
        val pending = state.value.filter { it.state == JobState.ENQUEUED && it.id !in running }
        if (pending.isEmpty()) {
            wakeUpPlanner.planWakeUp(null)
            return
        }

        wakeUpPlanner.planWakeUp(
            WakeUpPlan(
                atMillis = pending.minOf { it.earliestRunAtMillis }.coerceAtLeast(nowMillis),
                requiresNetwork = pending.any {
                    it.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED
                },
                requiresPower = pending.any {
                    it.constraints.requiresCharging || it.constraints.requiresDeviceIdle
                }
            )
        )
    }

    private suspend fun persistLocked() {
        try {
            storage.save(state.value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Failed to persist jobs", error)
        }
    }

    private sealed class Outcome {

        data object Interrupted : Outcome()

        data class Finished(val result: JobResult) : Outcome()
    }
}
