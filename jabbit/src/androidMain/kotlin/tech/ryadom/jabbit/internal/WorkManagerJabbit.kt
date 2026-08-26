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
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import java.util.UUID

internal class WorkManagerJabbit(context: Context, private val configuration: JabbitConfiguration) :
    Jabbit {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    override suspend fun enqueue(request: JobRequest) {
        enqueue(listOf(request))
    }

    override suspend fun enqueue(requests: List<JobRequest>) {
        if (requests.isEmpty()) {
            return
        }

        requests.forEach { configuration.requireKnownType(it.typeName) }
        requests.forEach { request ->
            configuration.notify { it.onEnqueued(request.toEnqueuedJobInfo(uniqueName = null)) }
        }
        workManager.enqueue(requests.map { it.toWorkRequest(uniqueName = null) }).result.await()
    }

    override suspend fun enqueueUnique(
        uniqueName: String,
        policy: ExistingJobPolicy,
        request: OneTimeJobRequest
    ) {
        configuration.requireKnownType(request.typeName)
        configuration.notify { it.onEnqueued(request.toEnqueuedJobInfo(uniqueName)) }
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
        configuration.requireKnownType(request.typeName)
        configuration.notify { it.onEnqueued(request.toEnqueuedJobInfo(uniqueName)) }
        workManager.enqueueUniquePeriodicWork(
            uniqueName,
            policy.toExistingPeriodicWorkPolicy(),
            request.toWorkRequest(uniqueName)
        ).result.await()
    }

    override suspend fun cancelJob(id: JobId) {
        val cancelled = listOfNotNull(getJobInfo(id))
        workManager.cancelWorkById(UUID.fromString(id.value)).result.await()
        notifyCancelled(cancelled)
    }

    override suspend fun cancelJobsByTag(tag: String) {
        val cancelled = getJobInfosByTag(tag)
        workManager.cancelAllWorkByTag(tag).result.await()
        notifyCancelled(cancelled)
    }

    override suspend fun cancelUniqueJob(uniqueName: String) {
        val cancelled = getJobInfosForUniqueJob(uniqueName)
        workManager.cancelUniqueWork(uniqueName).result.await()
        notifyCancelled(cancelled)
    }

    override suspend fun cancelAllJobs() {
        val cancelled = getJobInfosByTag(JABBIT_TAG)
        workManager.cancelAllWorkByTag(JABBIT_TAG).result.await()
        notifyCancelled(cancelled)
    }

    private fun notifyCancelled(jobs: List<JobInfo>) {
        jobs.filterNot { it.state.isFinished }.forEach { job ->
            configuration.notify { it.onCancelled(job.copy(state = JobState.CANCELLED)) }
        }
    }

    override suspend fun pruneFinishedJobs() {
        workManager.pruneWork().result.await()
    }

    override suspend fun getJobInfo(id: JobId): JobInfo? =
        workManager.getWorkInfoById(UUID.fromString(id.value)).await()
            ?.toJobInfo()

    override suspend fun getJobInfosByTag(tag: String): List<JobInfo> =
        workManager.getWorkInfosByTag(tag).await()
            .map { it.toJobInfo() }

    override suspend fun getJobInfosForUniqueJob(uniqueName: String): List<JobInfo> =
        workManager.getWorkInfosForUniqueWork(uniqueName).await()
            .map { it.toJobInfo() }

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
