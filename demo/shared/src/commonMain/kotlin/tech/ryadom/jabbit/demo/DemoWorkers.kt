package tech.ryadom.jabbit.demo

import kotlinx.coroutines.delay
import tech.ryadom.jabbit.JabbitWorker
import tech.ryadom.jabbit.JobExecution
import tech.ryadom.jabbit.JobResult
import tech.ryadom.jabbit.jobDataOf
import kotlin.random.Random

class UploadWorker : JabbitWorker {

    override suspend fun doWork(job: JobExecution): JobResult {
        val fileName = job.inputData.getString("file", "report.pdf")
        val chunks = job.inputData.getInt("chunks", 5)

        repeat(chunks) { index ->
            delay(CHUNK_MILLIS)
            job.setProgress(
                jobDataOf(
                    "percent" to (index + 1) * 100 / chunks,
                    "file" to fileName
                )
            )
        }

        if (job.inputData.getBoolean("flaky", false) && job.runAttemptCount == 0) {
            return JobResult.retry()
        }

        return JobResult.success(jobDataOf("uploaded" to fileName))
    }

    companion object {

        const val NAME: String = "upload"

        private const val CHUNK_MILLIS = 700L
    }
}

class SyncWorker : JabbitWorker {

    override suspend fun doWork(job: JobExecution): JobResult {
        delay(1_500)
        return JobResult.success(jobDataOf("items" to Random.nextInt(3, 40)))
    }

    companion object {

        const val NAME: String = "sync"
    }
}

class CleanupWorker : JabbitWorker {

    override suspend fun doWork(job: JobExecution): JobResult {
        delay(900)
        return JobResult.success(jobDataOf("removed" to Random.nextInt(0, 12)))
    }

    companion object {

        const val NAME: String = "cleanup"
    }
}

class BrokenWorker : JabbitWorker {

    override suspend fun doWork(job: JobExecution): JobResult {
        delay(600)
        return JobResult.failure(jobDataOf("reason" to "the server rejected this request"))
    }

    companion object {

        const val NAME: String = "broken"
    }
}
