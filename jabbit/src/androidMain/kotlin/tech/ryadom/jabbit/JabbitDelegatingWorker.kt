package tech.ryadom.jabbit

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import tech.ryadom.jabbit.internal.JabbitRuntime
import tech.ryadom.jabbit.internal.WORKER_NAME_KEY
import tech.ryadom.jabbit.internal.await
import tech.ryadom.jabbit.internal.toJobData
import tech.ryadom.jabbit.internal.toWorkData

/**
 * The single `androidx.work.ListenableWorker` every Jabbit job is scheduled as.
 *
 * `WorkManager` instantiates it by class name, and it resolves the actual [JabbitWorker] through
 * the [JabbitConfiguration] installed by `createJabbit`. It is public because `WorkManager`
 * requires it to be; applications never reference it directly.
 */
public class JabbitDelegatingWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val configuration = JabbitRuntime.configuration ?: return Result.failure()

        val workerName = inputData.getString(WORKER_NAME_KEY)
        if (workerName == null) {
            configuration.logger.error("Job $id carries no worker name")
            return Result.failure()
        }

        val worker = configuration.workerFactory.createWorker(workerName)
        if (worker == null) {
            configuration.logger.error("No worker registered for '$workerName'")
            return Result.failure()
        }

        val execution = JobExecution(
            id = JobId(id.toString()),
            workerName = workerName,
            inputData = inputData.toJobData(),
            tags = tags.filterNotTo(mutableSetOf()) {
                it.startsWith(RESERVED_TAG_PREFIX) || it == javaClass.name
            },
            runAttemptCount = runAttemptCount,
            progressReporter = { data ->
                setProgressAsync(data.toWorkData()).await()
            }
        )

        return when (val result = worker.doWork(execution)) {
            is JobResult.Success -> Result.success(result.outputData.toWorkData())
            is JobResult.Failure -> Result.failure(result.outputData.toWorkData())
            JobResult.Retry -> Result.retry()
        }
    }
}
