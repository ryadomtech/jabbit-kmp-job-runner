package tech.ryadom.jabbit.demo

import kotlinx.coroutines.flow.Flow
import tech.ryadom.jabbit.BackoffPolicy
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.NetworkType
import tech.ryadom.jabbit.constraints
import tech.ryadom.jabbit.jabbit
import tech.ryadom.jabbit.oneTimeJob
import tech.ryadom.jabbit.output
import tech.ryadom.jabbit.periodicJob
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

const val DEMO_TAG: String = "demo"

fun createDemoJabbit(): Jabbit = jabbit {
    worker(UploadJob) { UploadWorker() }
    worker(SyncJob) { SyncWorker() }
    worker(CleanupJob) { CleanupWorker() }
    worker(BrokenJob) { BrokenWorker() }
    logger(JabbitLogger.Console)

    android { }

    ios {
        backgroundTaskIdentifier = "tech.ryadom.jabbit.demo.jobs"
    }

    desktop {
        applicationName = "Jabbit Demo"
    }

    browser {
        queueName = "demo"
    }
}

class JabbitDemo(private val jabbit: Jabbit) {

    val jobs: Flow<List<JobInfo>> = jabbit.getJobInfosByTagFlow(DEMO_TAG)

    suspend fun uploadOverWifi() {
        jabbit.enqueue(
            oneTimeJob(UploadJob, UploadInput(fileName = "holiday-photos.zip", chunks = 6)) {
                setConstraints(constraints { requiredNetworkType = NetworkType.UNMETERED })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun uploadWithRetry() {
        jabbit.enqueue(
            oneTimeJob(
                UploadJob,
                UploadInput(fileName = "flaky-upload.bin", chunks = 3, flaky = true)
            ) {
                setBackoffCriteria(BackoffPolicy.LINEAR, 10.seconds)
                setMaxAttempts(3)
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun syncNow() {
        jabbit.enqueueUnique(
            uniqueName = "sync",
            policy = ExistingJobPolicy.KEEP,
            request = oneTimeJob(SyncJob) {
                setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun cleanupWhileCharging() {
        jabbit.enqueue(
            oneTimeJob(CleanupJob) {
                setConstraints(constraints { requiresCharging = true })
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun schedulePeriodicCleanup() {
        jabbit.enqueueUniquePeriodic(
            uniqueName = "cleanup",
            policy = ExistingPeriodicJobPolicy.KEEP,
            request = periodicJob(CleanupJob, repeatInterval = 6.hours) {
                addTag(DEMO_TAG)
            }
        )
    }

    suspend fun failingJob() {
        jabbit.enqueue(oneTimeJob(BrokenJob) { addTag(DEMO_TAG) })
    }

    suspend fun cancelEverything() {
        jabbit.cancelJobsByTag(DEMO_TAG)
    }

    suspend fun clearFinished() {
        jabbit.pruneFinishedJobs()
    }
}

fun JobInfo.contentToString(): String = buildString {
    append(typeName)
    uniqueName?.let { append(" · $it") }

    progress?.fraction?.let { append(" · ${(it * 100).toInt()}%") }
    output(UploadJob)?.let { append(" · ${it.fileName}") }
    output(SyncJob)?.let { append(" · ${it.items} items") }
    output(CleanupJob)?.let { append(" · ${it.removed} files removed") }
    failureReason?.let { append(" · $it") }

    if (runAttemptCount > 0) append(" · attempt ${runAttemptCount + 1}")
}
