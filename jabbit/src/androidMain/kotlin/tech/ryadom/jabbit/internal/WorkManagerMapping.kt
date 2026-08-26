package tech.ryadom.jabbit.internal

import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkRequest
import tech.ryadom.jabbit.BackoffPolicy
import tech.ryadom.jabbit.Constraints
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy
import tech.ryadom.jabbit.JabbitDelegatingWorker
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobProgress
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.NetworkType
import tech.ryadom.jabbit.OneTimeJobRequest
import tech.ryadom.jabbit.PeriodicJobRequest
import tech.ryadom.jabbit.RESERVED_TAG_PREFIX
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.work.BackoffPolicy as WorkBackoffPolicy
import androidx.work.Constraints as WorkConstraints
import androidx.work.NetworkType as WorkNetworkType

internal const val JABBIT_TAG = "${RESERVED_TAG_PREFIX}job"
internal const val TYPE_TAG_PREFIX = "${RESERVED_TAG_PREFIX}type."
internal const val UNIQUE_TAG_PREFIX = "${RESERVED_TAG_PREFIX}unique."
internal const val TYPE_NAME_KEY = "${RESERVED_TAG_PREFIX}typeName"
internal const val MAX_ATTEMPTS_KEY = "${RESERVED_TAG_PREFIX}maxAttempts"
internal const val INPUT_KEY = "${RESERVED_TAG_PREFIX}input"
internal const val OUTPUT_KEY = "${RESERVED_TAG_PREFIX}output"
internal const val FAILURE_KEY = "${RESERVED_TAG_PREFIX}failure"
internal const val PROGRESS_KEY = "${RESERVED_TAG_PREFIX}progress"

internal fun Constraints.toWorkConstraints(): WorkConstraints = WorkConstraints.Builder()
    .setRequiredNetworkType(requiredNetworkType.toWorkNetworkType())
    .setRequiresCharging(requiresCharging)
    .setRequiresBatteryNotLow(requiresBatteryNotLow)
    .setRequiresStorageNotLow(requiresStorageNotLow)
    .setRequiresDeviceIdle(requiresDeviceIdle)
    .build()

internal fun NetworkType.toWorkNetworkType(): WorkNetworkType = when (this) {
    NetworkType.NOT_REQUIRED -> WorkNetworkType.NOT_REQUIRED
    NetworkType.CONNECTED -> WorkNetworkType.CONNECTED
    NetworkType.UNMETERED -> WorkNetworkType.UNMETERED
    NetworkType.METERED -> WorkNetworkType.METERED
    NetworkType.NOT_ROAMING -> WorkNetworkType.NOT_ROAMING
}

internal fun BackoffPolicy.toWorkBackoffPolicy(): WorkBackoffPolicy = when (this) {
    BackoffPolicy.EXPONENTIAL -> WorkBackoffPolicy.EXPONENTIAL
    BackoffPolicy.LINEAR -> WorkBackoffPolicy.LINEAR
}

internal fun ExistingJobPolicy.toExistingWorkPolicy(): ExistingWorkPolicy = when (this) {
    ExistingJobPolicy.KEEP -> ExistingWorkPolicy.KEEP
    ExistingJobPolicy.REPLACE -> ExistingWorkPolicy.REPLACE
}

internal fun ExistingPeriodicJobPolicy.toExistingPeriodicWorkPolicy(): ExistingPeriodicWorkPolicy =
    when (this) {
        ExistingPeriodicJobPolicy.KEEP -> ExistingPeriodicWorkPolicy.KEEP

        ExistingPeriodicJobPolicy.UPDATE -> ExistingPeriodicWorkPolicy.UPDATE

        ExistingPeriodicJobPolicy.CANCEL_AND_REENQUEUE ->
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
    }

internal fun OneTimeJobRequest.toWorkRequest(uniqueName: String?): OneTimeWorkRequest =
    OneTimeWorkRequest.Builder(JabbitDelegatingWorker::class.java)
        .applyCommon(this, uniqueName)
        .build()

internal fun PeriodicJobRequest.toWorkRequest(uniqueName: String?): PeriodicWorkRequest =
    PeriodicWorkRequest.Builder(
        JabbitDelegatingWorker::class.java,
        repeatInterval.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
        flexInterval.inWholeMilliseconds,
        TimeUnit.MILLISECONDS
    )
        .applyCommon(this, uniqueName)
        .build()

internal fun JobRequest.toWorkRequest(uniqueName: String?): WorkRequest = when (this) {
    is OneTimeJobRequest -> toWorkRequest(uniqueName)
    is PeriodicJobRequest -> toWorkRequest(uniqueName)
}

private fun <B : WorkRequest.Builder<B, *>> B.applyCommon(
    request: JobRequest,
    uniqueName: String?
): B = apply {
    setId(UUID.fromString(request.id.value))
    setInputData(
        Data.Builder()
            .putString(TYPE_NAME_KEY, request.typeName)
            .putString(INPUT_KEY, request.encodedInput)
            .putInt(MAX_ATTEMPTS_KEY, request.maxAttempts)
            .build()
    )
    setConstraints(request.constraints.toWorkConstraints())
    setBackoffCriteria(
        request.backoffPolicy.toWorkBackoffPolicy(),
        request.backoffDelay.inWholeMilliseconds,
        TimeUnit.MILLISECONDS
    )
    if (request.initialDelay.inWholeMilliseconds > 0) {
        setInitialDelay(request.initialDelay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
    addTag(JABBIT_TAG)
    addTag(TYPE_TAG_PREFIX + request.typeName)
    uniqueName?.let { addTag(UNIQUE_TAG_PREFIX + it) }
    request.tags.forEach { addTag(it) }
}

internal fun JobRequest.toEnqueuedJobInfo(uniqueName: String?): JobInfo = JobInfo(
    id = id,
    typeName = typeName,
    state = JobState.ENQUEUED,
    tags = tags,
    uniqueName = uniqueName,
    progress = null,
    failureReason = null,
    runAttemptCount = 0,
    nextScheduleTimeMillis = null,
    encodedOutput = null
)

internal fun WorkInfo.toJobInfo(): JobInfo = JobInfo(
    id = JobId(id.toString()),
    typeName = tags.firstOrNull { it.startsWith(TYPE_TAG_PREFIX) }
        ?.removePrefix(TYPE_TAG_PREFIX)
        .orEmpty(),
    state = state.toJobState(),
    tags = tags.filterNotTo(mutableSetOf()) {
        it.startsWith(RESERVED_TAG_PREFIX) || it == JabbitDelegatingWorker::class.java.name
    },
    uniqueName = tags.firstOrNull { it.startsWith(UNIQUE_TAG_PREFIX) }
        ?.removePrefix(UNIQUE_TAG_PREFIX),
    progress = progress.getString(PROGRESS_KEY)?.let {
        decodePayload(JobProgress.serializer(), it)
    },
    failureReason = outputData.getString(FAILURE_KEY),
    runAttemptCount = runAttemptCount,
    nextScheduleTimeMillis = nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE },
    encodedOutput = outputData.getString(OUTPUT_KEY)
)

internal fun WorkInfo.State.toJobState(): JobState = when (this) {
    WorkInfo.State.ENQUEUED -> JobState.ENQUEUED
    WorkInfo.State.RUNNING -> JobState.RUNNING
    WorkInfo.State.SUCCEEDED -> JobState.SUCCEEDED
    WorkInfo.State.FAILED -> JobState.FAILED
    WorkInfo.State.BLOCKED -> JobState.BLOCKED
    WorkInfo.State.CANCELLED -> JobState.CANCELLED
}
