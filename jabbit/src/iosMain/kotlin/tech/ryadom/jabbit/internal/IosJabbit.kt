package tech.ryadom.jabbit.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest

internal class IosJabbit(
    private val engine: JobEngine
) : Jabbit {

    override suspend fun enqueue(request: JobRequest) {
        engine.enqueue(listOf(request))
    }

    override suspend fun enqueue(requests: List<JobRequest>) {
        if (requests.isEmpty()) return
        engine.enqueue(requests)
    }

    override suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    ) {
        engine.enqueueUnique(uniqueName, policy, request)
    }

    override suspend fun enqueueUniquePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        request: PeriodicJobRequest
    ) {
        engine.enqueueUniquePeriodic(uniqueName, policy, request)
    }

    override suspend fun cancelJob(id: JobId) {
        engine.cancel(id)
    }

    override suspend fun cancelJobsByTag(tag: String) {
        engine.cancelByTag(tag)
    }

    override suspend fun cancelUniqueJob(uniqueName: String) {
        engine.cancelUnique(uniqueName)
    }

    override suspend fun cancelAllJobs() {
        engine.cancelAll()
    }

    override suspend fun pruneFinishedJobs() {
        engine.prune()
    }

    override suspend fun getJobInfo(id: JobId): JobInfo? =
        engine.snapshot().firstOrNull { it.id == id.value }?.toJobInfo()

    override suspend fun getJobInfosByTag(tag: String): List<JobInfo> =
        engine.snapshot().filter { tag in it.tags }.map { it.toJobInfo() }

    override suspend fun getJobInfosForUniqueJob(uniqueName: String): List<JobInfo> =
        engine.snapshot().filter { it.uniqueName == uniqueName }.map { it.toJobInfo() }

    override fun getJobInfoFlow(id: JobId): Flow<JobInfo?> = engine.records
        .map { records -> records.firstOrNull { it.id == id.value }?.toJobInfo() }
        .distinctUntilChanged()

    override fun getJobInfosByTagFlow(tag: String): Flow<List<JobInfo>> = engine.records
        .map { records -> records.filter { tag in it.tags }.map { it.toJobInfo() } }
        .distinctUntilChanged()

    override fun getJobInfosForUniqueJobFlow(uniqueName: String): Flow<List<JobInfo>> =
        engine.records
            .map { records ->
                records.filter { it.uniqueName == uniqueName }.map { it.toJobInfo() }
            }
            .distinctUntilChanged()
}
