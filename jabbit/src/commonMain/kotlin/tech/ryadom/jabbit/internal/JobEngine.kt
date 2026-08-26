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
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobProgress
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.NetworkType
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import tech.ryadom.jabbit.debug
import tech.ryadom.jabbit.error

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
        records.forEach { configuration.requireKnownType(it.typeName) }
        initialization.await()
        mutex.withLock {
            records.forEach { putLocked(it) }
            persistLocked()
            records.forEach { record -> configuration.notify { it.onEnqueued(record.toJobInfo()) } }
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
        configuration.requireKnownType(record.typeName)
        initialization.await()
        mutex.withLock {
            val existing = unfinishedUniqueLocked(uniqueName)
            when {
                existing == null -> Unit

                policy == ExistingJobPolicy.KEEP -> {
                    logger.debug("Unique job '$uniqueName' already exists, keeping it")
                    return@withLock
                }

                else -> dropLocked(existing.id)
            }
            putLocked(record)
            persistLocked()
            configuration.notify { it.onEnqueued(record.toJobInfo()) }
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
        configuration.requireKnownType(record.typeName)
        initialization.await()
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

                else -> dropLocked(existing.id)
            }
            putLocked(record)
            persistLocked()
            configuration.notify { it.onEnqueued(record.toJobInfo()) }
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

                if (running.size >= configuration.maxConcurrentJobs) break

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
        val registration = configuration.registrationOf(record.typeName)
        if (registration == null) {
            logger.error("No worker registered for '${record.typeName}', failing job ${record.id}")
            val failed = record.copy(
                state = JobState.FAILED,
                finishedAtMillis = nowMillis,
                failureReason = "no worker is registered for '${record.typeName}'",
                progress = null
            )
            putLocked(failed)
            persistLocked()
            configuration.notify { it.onFailed(failed.toJobInfo(), null) }
            return
        }

        val started = record.copy(state = JobState.RUNNING)
        putLocked(started)
        persistLocked()
        configuration.notify { it.onStarted(started.toJobInfo()) }

        val job = scope.launch {
            var failure: Throwable? = null
            val outcome = registration.runCatchingCancellation(
                id = JobId(record.id),
                encodedInput = record.encodedInput,
                tags = record.tags,
                runAttemptCount = record.runAttemptCount,
                onProgress = { progress -> publishProgress(record.id, progress) },
                onError = { error ->
                    failure = error
                    logger.error(
                        "Worker '${record.typeName}' threw, failing job ${record.id}",
                        error
                    )
                }
            )
            withContext(NonCancellable) {
                complete(record.id, Outcome.Finished(outcome), failure)
            }
        }

        job.invokeOnCompletion { cause ->
            if (cause != null) {
                scope.launch {
                    withContext(NonCancellable) {
                        complete(record.id, Outcome.Interrupted, error = null)
                    }
                }
            }
        }

        running[record.id] = job
    }

    private suspend fun complete(id: String, outcome: Outcome, error: Throwable?) {
        val now = clock.nowMillis()
        var notification: (() -> Unit)? = null

        mutex.withLock {
            running.remove(id)
            val record = state.value.firstOrNull { it.id == id } ?: return@withLock
            if (record.state != JobState.RUNNING) return@withLock

            val updated = when (outcome) {
                Outcome.Interrupted -> record.retrying(now, applyBackoff = false)

                is Outcome.Finished -> when (val result = outcome.result) {
                    is WorkerOutcome.Succeeded -> record.succeeded(now, result.encodedOutput)

                    is WorkerOutcome.Failed -> record.copy(
                        state = JobState.FAILED,
                        finishedAtMillis = now,
                        failureReason = result.reason,
                        progress = null
                    )

                    WorkerOutcome.Retry -> record.retrying(now, applyBackoff = true)
                }
            }

            putLocked(updated)
            persistLocked()

            val info = updated.toJobInfo()
            val succeeded = outcome is Outcome.Finished &&
                outcome.result is WorkerOutcome.Succeeded

            notification = when {
                outcome is Outcome.Interrupted -> {
                    { configuration.notify { it.onStopped(info) } }
                }

                succeeded -> {
                    { configuration.notify { it.onSucceeded(info) } }
                }

                updated.state == JobState.FAILED -> {
                    { configuration.notify { it.onFailed(info, error) } }
                }

                else -> {
                    {
                        configuration.notify {
                            it.onRetryScheduled(
                                info,
                                updated.earliestRunAtMillis - now
                            )
                        }
                    }
                }
            }
        }

        notification?.invoke()
        wakeUp()
    }

    private suspend fun publishProgress(id: String, progress: JobProgress) {
        mutex.withLock {
            val record = state.value.firstOrNull { it.id == id } ?: return@withLock
            if (record.state != JobState.RUNNING) return@withLock
            putLocked(record.copy(progress = progress))
        }
    }

    private fun JobRecord.succeeded(nowMillis: Long, encodedOutput: String): JobRecord = when {
        isPeriodic -> nextPeriod(nowMillis).copy(encodedOutput = encodedOutput)

        else -> copy(
            state = JobState.SUCCEEDED,
            finishedAtMillis = nowMillis,
            encodedOutput = encodedOutput,
            progress = null
        )
    }

    private fun JobRecord.retrying(nowMillis: Long, applyBackoff: Boolean): JobRecord {
        val attempt = runAttemptCount + 1
        if (attempt >= maxAttempts) {
            logger.debug("Job $id ran $attempt times and is out of attempts, failing it")
            return copy(
                state = JobState.FAILED,
                finishedAtMillis = nowMillis,
                failureReason = "gave up after $attempt attempts",
                progress = null
            )
        }

        val delay = if (applyBackoff) {
            backoffDelayMillis(backoffPolicy, backoffDelayMillis, attempt)
        } else {
            0L
        }
        return copy(
            state = JobState.ENQUEUED,
            runAttemptCount = attempt,
            earliestRunAtMillis = nowMillis + delay,
            progress = null
        )
    }

    private fun cancelLocked(id: String, nowMillis: Long) {
        running.remove(id)?.cancel()
        val record = state.value.firstOrNull { it.id == id } ?: return
        if (record.state.isFinished) return

        val cancelled = record.copy(
            state = JobState.CANCELLED,
            finishedAtMillis = nowMillis,
            progress = null
        )
        putLocked(cancelled)
        configuration.notify { it.onCancelled(cancelled.toJobInfo()) }
    }

    private fun dropLocked(id: String) {
        running.remove(id)?.cancel()
        val dropped = state.value.firstOrNull { it.id == id }
        state.value = state.value.filterNot { it.id == id }
        dropped?.let { record -> configuration.notify { it.onCancelled(record.toJobInfo()) } }
    }

    private fun unfinishedUniqueLocked(uniqueName: String): JobRecord? = state.value.firstOrNull {
        it.uniqueName == uniqueName && !it.state.isFinished
    }

    private fun putLocked(record: JobRecord) {
        val current = state.value
        val index = current.indexOfFirst { it.id == record.id }
        state.value = if (index < 0) {
            current + record
        } else {
            current.toMutableList().also {
                it[index] = record
            }
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

        data class Finished(val result: WorkerOutcome) : Outcome()
    }
}
