package tech.ryadom.jabbit.demo

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import tech.ryadom.jabbit.JabbitWorker
import tech.ryadom.jabbit.JobExecution
import tech.ryadom.jabbit.JobProgress
import tech.ryadom.jabbit.JobResult
import tech.ryadom.jabbit.jobType
import kotlin.random.Random

@Serializable
data class UploadInput(val fileName: String, val chunks: Int = 5, val flaky: Boolean = false)

@Serializable
data class UploadOutput(val fileName: String)

@Serializable
data class SyncOutput(val items: Int)

@Serializable
data class CleanupOutput(val removed: Int)

val UploadJob = jobType<UploadInput, UploadOutput>("upload")

val SyncJob = jobType<Unit, SyncOutput>("sync")

val CleanupJob = jobType<Unit, CleanupOutput>("cleanup")

val BrokenJob = jobType<Unit, Unit>("broken")

class UploadWorker : JabbitWorker<UploadInput, UploadOutput> {

    override suspend fun doWork(job: JobExecution<UploadInput>): JobResult<UploadOutput> {
        val chunks = job.input.chunks

        repeat(chunks) { index ->
            delay(CHUNK_MILLIS)
            job.setProgress(
                JobProgress(
                    fraction = (index + 1) / chunks.toFloat(),
                    message = "uploading ${job.input.fileName}"
                )
            )
        }

        if (job.input.flaky && job.runAttemptCount == 0) {
            return JobResult.retry()
        }

        return JobResult.success(UploadOutput(job.input.fileName))
    }

    private companion object {

        const val CHUNK_MILLIS = 700L
    }
}

class SyncWorker : JabbitWorker<Unit, SyncOutput> {

    override suspend fun doWork(job: JobExecution<Unit>): JobResult<SyncOutput> {
        delay(1_500)
        return JobResult.success(SyncOutput(Random.nextInt(3, 40)))
    }
}

class CleanupWorker : JabbitWorker<Unit, CleanupOutput> {

    override suspend fun doWork(job: JobExecution<Unit>): JobResult<CleanupOutput> {
        delay(900)
        return JobResult.success(CleanupOutput(Random.nextInt(0, 12)))
    }
}

class BrokenWorker : JabbitWorker<Unit, Unit> {

    override suspend fun doWork(job: JobExecution<Unit>): JobResult<Unit> {
        delay(600)
        return JobResult.failure("the server rejected this request")
    }
}
