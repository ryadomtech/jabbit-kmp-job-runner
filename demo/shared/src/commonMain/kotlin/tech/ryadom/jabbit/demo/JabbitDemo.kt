package tech.ryadom.jabbit.demo

import kotlinx.coroutines.flow.Flow
import tech.ryadom.jabbit.BackoffPolicy
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitConfiguration
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.NetworkType
import tech.ryadom.jabbit.constraints
import tech.ryadom.jabbit.jabbitConfiguration
import tech.ryadom.jabbit.jobDataOf
import tech.ryadom.jabbit.oneTimeJob
import tech.ryadom.jabbit.periodicJob
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private const val DEMO_TAG: String = "demo"

fun demoConfiguration(): JabbitConfiguration = jabbitConfiguration {
    worker(UploadWorker.NAME) { UploadWorker() }
    worker(SyncWorker.NAME) { SyncWorker() }
    worker(CleanupWorker.NAME) { CleanupWorker() }
    worker(BrokenWorker.NAME) { BrokenWorker() }
    logger(JabbitLogger.Console)
}

class JabbitDemo(
    private val jabbit: Jabbit
) {

    val jobs: Flow<List<JobInfo>> = jabbit.getJobInfosByTagFlow(DEMO_TAG)

    suspend fun uploadOverWifi() {
        jabbit.enqueue(
            oneTimeJob(UploadWorker.NAME) {
                setInputData(jobDataOf("file" to "holiday-photos.zip", "chunks" to 6))
                setConstraints(constraints { requiredNetworkType = NetworkType.UNMETERED })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun uploadWithRetry() {
        jabbit.enqueue(
            oneTimeJob(UploadWorker.NAME) {
                setInputData(
                    jobDataOf(
                        "file" to "flaky-upload.bin",
                        "chunks" to 3,
                        "flaky" to true
                    )
                )
                setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds)
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun syncNow() {
        jabbit.enqueueUnique(
            uniqueName = "sync",
            policy = ExistingJobPolicy.KEEP,
            request = oneTimeJob(SyncWorker.NAME) {
                setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun cleanupWhileCharging() {
        jabbit.enqueue(
            oneTimeJob(CleanupWorker.NAME) {
                setConstraints(constraints { requiresCharging = true })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun schedulePeriodicCleanup() {
        jabbit.enqueueUniquePeriodic(
            uniqueName = "cleanup",
            policy = ExistingPeriodicJobPolicy.KEEP,
            request = periodicJob(CleanupWorker.NAME, repeatInterval = 6.hours) {
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun failingJob() {
        jabbit.enqueue(
            oneTimeJob(BrokenWorker.NAME) {
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun cancelEverything() {
        jabbit.cancelJobsByTag(DEMO_TAG)
    }

    suspend fun clearFinished() {
        jabbit.pruneFinishedJobs()
    }
}

fun JobInfo.contentToString(): String = buildString {
    append(workerName)
    uniqueName?.let { append(" · $it") }

    progress.getInt("percent")?.let { append(" · $it%") }
    outputData.getString("uploaded")?.let { append(" · $it") }
    outputData.getInt("items")?.let { append(" · $it items") }
    outputData.getInt("removed")?.let { append(" · $it files removed") }
    outputData.getString("reason")?.let { append(" · $it") }

    if (runAttemptCount > 0) append(" · attempt ${runAttemptCount + 1}")
}
