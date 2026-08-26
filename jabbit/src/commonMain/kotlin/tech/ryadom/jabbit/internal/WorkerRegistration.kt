package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CancellationException
import tech.ryadom.jabbit.JabbitWorker
import tech.ryadom.jabbit.JobExecution
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobProgress
import tech.ryadom.jabbit.JobResult
import tech.ryadom.jabbit.JobType
import tech.ryadom.jabbit.ProgressReporter

internal sealed class WorkerOutcome {

    data class Succeeded(val encodedOutput: String) : WorkerOutcome()

    data class Failed(val reason: String?) : WorkerOutcome()

    data object Retry : WorkerOutcome()
}

internal class WorkerRegistration<I, O>(
    private val type: JobType<I, O>,
    private val factory: () -> JabbitWorker<I, O>
) {

    val name: String get() = type.name

    suspend fun run(
        id: JobId,
        encodedInput: String?,
        tags: Set<String>,
        runAttemptCount: Int,
        onProgress: suspend (JobProgress) -> Unit
    ): WorkerOutcome {
        val input = try {
            JabbitJson.decodeFromString(type.inputSerializer, encodedInput ?: NULL_PAYLOAD)
        } catch (error: Throwable) {
            return WorkerOutcome.Failed(
                "job '${type.name}' was enqueued with a payload it can no longer read"
            )
        }

        val execution = JobExecution.forTesting(
            input = input,
            id = id,
            typeName = type.name,
            tags = tags,
            runAttemptCount = runAttemptCount,
            progressReporter = ProgressReporter { onProgress(it) }
        )

        return when (val result = factory().doWork(execution)) {
            is JobResult.Success -> WorkerOutcome.Succeeded(
                encodePayload(type.outputSerializer, result.output)
            )

            is JobResult.Failure -> WorkerOutcome.Failed(result.reason)

            JobResult.Retry -> WorkerOutcome.Retry
        }
    }

    private companion object {

        const val NULL_PAYLOAD = "null"
    }
}

internal suspend fun WorkerRegistration<*, *>.runCatchingCancellation(
    id: JobId,
    encodedInput: String?,
    tags: Set<String>,
    runAttemptCount: Int,
    onProgress: suspend (JobProgress) -> Unit,
    onError: (Throwable) -> Unit
): WorkerOutcome = try {
    run(id, encodedInput, tags, runAttemptCount, onProgress)
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    onError(error)
    WorkerOutcome.Failed(error.message)
}
