package tech.ryadom.jabbit

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import tech.ryadom.jabbit.JabbitDelegatingWorker.Companion.PROGRESS_INTERVAL_MILLIS
import tech.ryadom.jabbit.internal.FAILURE_KEY
import tech.ryadom.jabbit.internal.INPUT_KEY
import tech.ryadom.jabbit.internal.JabbitRuntime
import tech.ryadom.jabbit.internal.MAX_ATTEMPTS_KEY
import tech.ryadom.jabbit.internal.OUTPUT_KEY
import tech.ryadom.jabbit.internal.PROGRESS_KEY
import tech.ryadom.jabbit.internal.TYPE_NAME_KEY
import tech.ryadom.jabbit.internal.UNIQUE_TAG_PREFIX
import tech.ryadom.jabbit.internal.WorkerOutcome
import tech.ryadom.jabbit.internal.await
import tech.ryadom.jabbit.internal.encodePayload
import tech.ryadom.jabbit.internal.runCatchingCancellation

/**
 * The single `androidx.work.ListenableWorker` every Jabbit job is scheduled as.
 *
 * `WorkManager` instantiates it by class name, and it resolves the actual [JabbitWorker] through
 * the description installed by `jabbit { }`. It is public because `WorkManager` requires it to be;
 * applications never reference it directly.
 */
public class JabbitDelegatingWorker(appContext: Context, parameters: WorkerParameters) :
    CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val configuration = JabbitRuntime.configuration
        if (configuration == null) {
            Log.e(
                "Jabbit",
                "Jabbit was never created in this process, so job $id cannot run. " +
                    "Call jabbit { } from Application.onCreate."
            )
            return Result.failure()
        }

        val typeName = inputData.getString(TYPE_NAME_KEY)
        if (typeName == null) {
            configuration.logger.error("Job $id carries no job type")
            return Result.failure()
        }

        val maxAttempts = inputData.getInt(MAX_ATTEMPTS_KEY, JobRequest.UNLIMITED_ATTEMPTS)
        if (runAttemptCount >= maxAttempts) {
            return Result.failure(
                failureData("gave up after $runAttemptCount attempts")
            )
        }

        val registration = configuration.registrationOf(typeName)
        if (registration == null) {
            configuration.logger.error("No worker registered for '$typeName'")
            val reason = "no worker is registered for '$typeName'"
            configuration.notify { it.onFailed(jobInfo(typeName, JobState.FAILED, reason), null) }
            return Result.failure(failureData(reason))
        }

        configuration.notify { it.onStarted(jobInfo(typeName, JobState.RUNNING)) }

        val reported = MutableStateFlow<JobProgress?>(null)
        var failure: Throwable? = null

        val outcome = coroutineScope {
            val writer = launch { publishProgress(reported) }

            try {
                registration.runCatchingCancellation(
                    id = JobId(id.toString()),
                    encodedInput = inputData.getString(INPUT_KEY),
                    tags = tags.filterNotTo(mutableSetOf()) {
                        it.startsWith(RESERVED_TAG_PREFIX) || it == javaClass.name
                    },
                    runAttemptCount = runAttemptCount,
                    onProgress = { progress -> reported.value = progress },
                    onError = { error ->
                        failure = error
                        configuration.logger.error(
                            "Worker '$typeName' threw, failing job $id",
                            error
                        )
                    }
                )
            } finally {
                writer.cancel()
            }
        }

        return when (outcome) {
            is WorkerOutcome.Succeeded -> {
                configuration.notify {
                    it.onSucceeded(
                        jobInfo(typeName, JobState.SUCCEEDED, output = outcome.encodedOutput)
                    )
                }
                Result.success(
                    Data.Builder().putString(OUTPUT_KEY, outcome.encodedOutput).build()
                )
            }

            is WorkerOutcome.Failed -> {
                configuration.notify {
                    it.onFailed(jobInfo(typeName, JobState.FAILED, outcome.reason), failure)
                }
                Result.failure(failureData(outcome.reason))
            }

            WorkerOutcome.Retry -> when {
                runAttemptCount + 1 >= maxAttempts -> {
                    val reason = "gave up after ${runAttemptCount + 1} attempts"
                    configuration.notify {
                        it.onFailed(jobInfo(typeName, JobState.FAILED, reason), null)
                    }
                    Result.failure(failureData(reason))
                }

                else -> {
                    configuration.notify {
                        it.onRetryScheduled(
                            jobInfo(typeName, JobState.ENQUEUED),
                            delayMillis = null
                        )
                    }
                    Result.retry()
                }
            }
        }
    }

    private fun jobInfo(
        typeName: String,
        state: JobState,
        reason: String? = null,
        output: String? = null
    ): JobInfo = JobInfo(
        id = JobId(id.toString()),
        typeName = typeName,
        state = state,
        tags = tags.filterNotTo(mutableSetOf()) {
            it.startsWith(RESERVED_TAG_PREFIX) || it == javaClass.name
        },
        uniqueName = tags.firstOrNull { it.startsWith(UNIQUE_TAG_PREFIX) }
            ?.removePrefix(UNIQUE_TAG_PREFIX),
        progress = null,
        failureReason = reason,
        runAttemptCount = runAttemptCount,
        nextScheduleTimeMillis = null,
        encodedOutput = output
    )

    /**
     * Writes at most one progress update per [PROGRESS_INTERVAL_MILLIS], always the latest one.
     *
     * Every update goes through the database of `WorkManager`, so a worker reporting in a tight
     * loop would otherwise spend its time writing rows nobody reads.
     */
    private suspend fun publishProgress(reported: StateFlow<JobProgress?>) {
        reported.filterNotNull().collect { progress ->
            setProgressAsync(
                Data.Builder()
                    .putString(PROGRESS_KEY, encodePayload(JobProgress.serializer(), progress))
                    .build()
            ).await()
            delay(PROGRESS_INTERVAL_MILLIS)
        }
    }

    private fun failureData(reason: String?): Data = Data.Builder()
        .apply { reason?.let { putString(FAILURE_KEY, it) } }
        .build()

    private companion object {

        const val PROGRESS_INTERVAL_MILLIS = 500L
    }
}
