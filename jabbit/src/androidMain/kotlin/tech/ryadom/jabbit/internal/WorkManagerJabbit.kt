package tech.ryadom.jabbit.internal

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitConfiguration
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import tech.ryadom.jabbit.requireKnownWorker
import java.util.UUID

internal class WorkManagerJabbit(
    context: Context,
    private val configuration: JabbitConfiguration
) : Jabbit {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun enqueue(request: JobRequest) {
        enqueue(listOf(request))
    }

    override suspend fun enqueue(requests: List<JobRequest>) {
        if (requests.isEmpty()) {
            return
        }

        requests.forEach { configuration.requireKnownWorker(it.workerName) }
        workManager.enqueue(requests.map { it.toWorkRequest(uniqueName = null) }).result.await()
    }

    override suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    ) {
        configuration.requireKnownWorker(request.workerName)
        workManager.enqueueUniqueWork(
            uniqueName,
            policy.toExistingWorkPolicy(),
            request.toWorkRequest(uniqueName)
        ).result.await()
    }

    override suspend fun enqueueUniquePeriodic(
        uniqueName: String,
        policy: ExistingPeriodicJobPolicy,
        request: PeriodicJobRequest
    ) {
        configuration.requireKnownWorker(request.workerName)
        workManager.enqueueUniquePeriodicWork(
            uniqueName,
            policy.toExistingPeriodicWorkPolicy(),
            request.toWorkRequest(uniqueName)
        ).result.await()
    }

    override suspend fun cancelJob(id: JobId) {
        workManager.cancelWorkById(UUID.fromString(id.value)).result.await()
    }

    override suspend fun cancelJobsByTag(tag: String) {
        workManager.cancelAllWorkByTag(tag).result.await()
    }

    override suspend fun cancelUniqueJob(uniqueName: String) {
        workManager.cancelUniqueWork(uniqueName).result.await()
    }

    override suspend fun cancelAllJobs() {
        workManager.cancelAllWorkByTag(JABBIT_TAG).result.await()
    }

    override suspend fun pruneFinishedJobs() {
        workManager.pruneWork().result.await()
    }

    override suspend fun getJobInfo(id: JobId): JobInfo? =
        workManager.getWorkInfoById(UUID.fromString(id.value)).await()?.toJobInfo()

    override suspend fun getJobInfosByTag(tag: String): List<JobInfo> =
        workManager.getWorkInfosByTag(tag).await().map { it.toJobInfo() }

    override suspend fun getJobInfosForUniqueJob(uniqueName: String): List<JobInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueName).await().map { it.toJobInfo() }

    override fun getJobInfoFlow(id: JobId): Flow<JobInfo?> =
        workManager.getWorkInfoByIdFlow(UUID.fromString(id.value))
            .map { it?.toJobInfo() }

    override fun getJobInfosByTagFlow(tag: String): Flow<List<JobInfo>> =
        workManager.getWorkInfosByTagFlow(tag)
            .map { infos -> infos.map { it.toJobInfo() } }

    override fun getJobInfosForUniqueJobFlow(uniqueName: String): Flow<List<JobInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
            .map { infos -> infos.map { it.toJobInfo() } }
}
