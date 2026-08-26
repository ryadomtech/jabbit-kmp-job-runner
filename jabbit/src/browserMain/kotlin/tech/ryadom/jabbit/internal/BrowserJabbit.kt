package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitBrowserOptions
import tech.ryadom.jabbit.JabbitConfiguration
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import tech.ryadom.jabbit.debug
import tech.ryadom.jabbit.warn

private const val LEADERSHIP_TIMEOUT_MILLIS = 2_000L

internal class BrowserJabbit(
    private val configuration: JabbitConfiguration,
    private val options: JabbitBrowserOptions,
    private val engine: JobEngine,
    private val storage: JobRecordStorage,
    private val coordinator: TabCoordinator,
    private val planner: BrowserWakeUpPlanner,
    private val scope: CoroutineScope,
    private val clock: JabbitClock
) : Jabbit {

    private val logger = configuration.logger
    private val records = MutableStateFlow<List<JobRecord>>(emptyList())
    private val leader = MutableStateFlow<Boolean?>(null)

    fun start() {
        planner.registerPeriodicWakeUp()

        scope.launch {
            val restored = try {
                storage.load()
            } catch (error: Throwable) {
                logger.warn("Could not read the persisted queue", error)
                emptyList()
            }
            if (records.value.isEmpty()) records.value = restored
        }

        if (!options.coordinateTabs) {
            becomeLeader()
            return
        }

        coordinator.start(::onMessage)
        if (!coordinator.isAvailable || !supportsWebLocks()) {
            logger.warn("This browser cannot elect a tab, every tab will run its own jobs")
            becomeLeader()
            return
        }

        coordinator.send(TabMessage.RequestState)
        holdExclusiveLock(options.leaderLockName) { becomeLeader() }

        scope.launch {
            delay(LEADERSHIP_TIMEOUT_MILLIS)
            if (leader.value == null) {
                logger.warn("No tab answered in time, running as a follower")
                leader.value = false
            }
        }
    }

    override suspend fun enqueue(request: JobRequest) {
        enqueue(listOf(request))
    }

    override suspend fun enqueue(requests: List<JobRequest>) {
        if (requests.isEmpty()) return
        requests.forEach { configuration.requireKnownType(it.typeName) }
        val now = clock.nowMillis()
        val enqueued = requests.map { it.toRecord(uniqueName = null, nowMillis = now) }
        dispatch(TabMessage.Enqueue(enqueued)) { engine.enqueueRecords(enqueued) }
    }

    override suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    ) {
        configuration.requireKnownType(request.typeName)
        val record = request.toRecord(uniqueName = uniqueName, nowMillis = clock.nowMillis())
        dispatch(TabMessage.EnqueueUnique(uniqueName, policy, record)) {
            engine.enqueueUniqueRecord(uniqueName, policy, record)
        }
    }

    override suspend fun enqueueUniquePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        request: PeriodicJobRequest
    ) {
        configuration.requireKnownType(request.typeName)
        val record = request.toRecord(uniqueName = uniqueName, nowMillis = clock.nowMillis())
        dispatch(TabMessage.EnqueueUniquePeriodic(uniqueName, policy, record)) {
            engine.enqueueUniquePeriodicRecord(uniqueName, policy, record)
        }
    }

    override suspend fun cancelJob(id: JobId) {
        dispatch(TabMessage.CancelJob(id.value)) { engine.cancel(id) }
    }

    override suspend fun cancelJobsByTag(tag: String) {
        dispatch(TabMessage.CancelByTag(tag)) { engine.cancelByTag(tag) }
    }

    override suspend fun cancelUniqueJob(uniqueName: String) {
        dispatch(TabMessage.CancelUnique(uniqueName)) { engine.cancelUnique(uniqueName) }
    }

    override suspend fun cancelAllJobs() {
        dispatch(TabMessage.CancelAll) { engine.cancelAll() }
    }

    override suspend fun pruneFinishedJobs() {
        dispatch(TabMessage.Prune) { engine.prune() }
    }

    override suspend fun getJobInfo(id: JobId): JobInfo? = records.value.firstOrNull {
        it.id == id.value
    }?.toJobInfo()

    override suspend fun getJobInfosByTag(tag: String): List<JobInfo> = records.value.filter {
        tag in it.tags
    }.map { it.toJobInfo() }

    override suspend fun getJobInfosForUniqueJob(uniqueName: String): List<JobInfo> =
        records.value.filter {
            it.uniqueName == uniqueName
        }.map { it.toJobInfo() }

    override fun getJobInfoFlow(id: JobId): Flow<JobInfo?> = records
        .map { snapshot -> snapshot.firstOrNull { it.id == id.value }?.toJobInfo() }
        .distinctUntilChanged()

    override fun getJobInfosByTagFlow(tag: String): Flow<List<JobInfo>> = records
        .map { snapshot -> snapshot.filter { tag in it.tags }.map { it.toJobInfo() } }
        .distinctUntilChanged()

    override fun getJobInfosForUniqueJobFlow(uniqueName: String): Flow<List<JobInfo>> = records
        .map { snapshot ->
            snapshot.filter { it.uniqueName == uniqueName }.map { it.toJobInfo() }
        }
        .distinctUntilChanged()

    private suspend fun dispatch(command: TabMessage, apply: suspend () -> Unit) {
        if (leader.filterNotNull().first()) apply() else coordinator.send(command)
    }

    private fun becomeLeader() {
        if (leader.value == true) return
        leader.value = true
        logger.debug("This tab now runs the job queue")

        engine.start()
        scope.launch {
            engine.records.collect { snapshot ->
                records.value = snapshot
                coordinator.send(TabMessage.State(snapshot))
            }
        }
    }

    private fun onMessage(message: TabMessage) {
        when (message) {
            is TabMessage.State -> {
                if (leader.value == true) return
                leader.value = false
                records.value = message.records
            }

            TabMessage.RequestState -> {
                if (leader.value == true) coordinator.send(TabMessage.State(records.value))
            }

            else -> {
                if (leader.value != true) return
                scope.launch {
                    try {
                        apply(message)
                    } catch (error: Throwable) {
                        logger.warn("Ignoring a command this tab cannot run", error)
                    }
                }
            }
        }
    }

    private suspend fun apply(message: TabMessage) {
        when (message) {
            is TabMessage.Enqueue -> engine.enqueueRecords(message.records)

            is TabMessage.EnqueueUnique -> engine.enqueueUniqueRecord(
                uniqueName = message.uniqueName,
                policy = message.policy,
                record = message.record
            )

            is TabMessage.EnqueueUniquePeriodic -> engine.enqueueUniquePeriodicRecord(
                uniqueName = message.uniqueName,
                policy = message.policy,
                record = message.record
            )

            is TabMessage.CancelJob -> engine.cancel(JobId(message.id))

            is TabMessage.CancelByTag -> engine.cancelByTag(message.tag)

            is TabMessage.CancelUnique -> engine.cancelUnique(message.uniqueName)

            TabMessage.CancelAll -> engine.cancelAll()

            TabMessage.Prune -> engine.prune()

            TabMessage.RequestState, is TabMessage.State -> Unit
        }
    }
}
