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
import tech.ryadom.jabbit.JobData
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
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

internal const val WORKER_TAG_PREFIX = "${RESERVED_TAG_PREFIX}worker."

internal const val UNIQUE_TAG_PREFIX = "${RESERVED_TAG_PREFIX}unique."

internal const val WORKER_NAME_KEY = "${RESERVED_TAG_PREFIX}workerName"

private const val VALUE_KEY_PREFIX = "${RESERVED_TAG_PREFIX}value."

internal fun JobData.putInto(builder: Data.Builder): Data.Builder {
    values.forEach { (key, value) ->
        val dataKey = VALUE_KEY_PREFIX + key
        when (value) {
            is JobDataValue.BooleanValue -> builder.putBoolean(dataKey, value.value)
            is JobDataValue.IntValue -> builder.putInt(dataKey, value.value)
            is JobDataValue.LongValue -> builder.putLong(dataKey, value.value)
            is JobDataValue.FloatValue -> builder.putFloat(dataKey, value.value)
            is JobDataValue.DoubleValue -> builder.putDouble(dataKey, value.value)
            is JobDataValue.StringValue -> builder.putString(dataKey, value.value)
            is JobDataValue.StringListValue -> builder.putStringArray(
                dataKey,
                value.value.toTypedArray()
            )
        }
    }

    return builder
}

internal fun JobData.toWorkData(): Data = putInto(Data.Builder()).build()

internal fun Data.toJobData(): JobData {
    val values = buildMap {
        keyValueMap.forEach { (key, raw) ->
            if (!key.startsWith(VALUE_KEY_PREFIX)) return@forEach
            val value = when (raw) {
                is Boolean -> JobDataValue.BooleanValue(raw)
                is Int -> JobDataValue.IntValue(raw)
                is Long -> JobDataValue.LongValue(raw)
                is Float -> JobDataValue.FloatValue(raw)
                is Double -> JobDataValue.DoubleValue(raw)
                is String -> JobDataValue.StringValue(raw)
                is Array<*> -> JobDataValue.StringListValue(raw.filterIsInstance<String>())
                else -> null
            }

            if (value != null) {
                put(key.removePrefix(VALUE_KEY_PREFIX), value)
            }
        }
    }

    return JobData(values)
}

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
        workerClass = JabbitDelegatingWorker::class.java,
        repeatInterval = repeatInterval.inWholeMilliseconds,
        repeatIntervalTimeUnit = TimeUnit.MILLISECONDS,
        flexInterval = flexInterval.inWholeMilliseconds,
        flexIntervalTimeUnit = TimeUnit.MILLISECONDS
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
        request.inputData
            .putInto(Data.Builder().putString(WORKER_NAME_KEY, request.workerName))
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
    addTag(WORKER_TAG_PREFIX + request.workerName)
    uniqueName?.let { addTag(UNIQUE_TAG_PREFIX + it) }
    request.tags.forEach { addTag(it) }
}

internal fun WorkInfo.toJobInfo(): JobInfo = JobInfo(
    id = JobId(id.toString()),
    workerName = tags.firstOrNull { it.startsWith(WORKER_TAG_PREFIX) }
        ?.removePrefix(WORKER_TAG_PREFIX)
        .orEmpty(),
    state = state.toJobState(),
    tags = tags.filterNotTo(mutableSetOf()) {
        it.startsWith(RESERVED_TAG_PREFIX) || it == JabbitDelegatingWorker::class.java.name
    },
    uniqueName = tags.firstOrNull { it.startsWith(UNIQUE_TAG_PREFIX) }
        ?.removePrefix(UNIQUE_TAG_PREFIX),
    outputData = outputData.toJobData(),
    progress = progress.toJobData(),
    runAttemptCount = runAttemptCount,
    nextScheduleTimeMillis = nextScheduleTimeMillis.takeIf { it != Long.MAX_VALUE }
)

internal fun WorkInfo.State.toJobState(): JobState = when (this) {
    WorkInfo.State.ENQUEUED -> JobState.ENQUEUED
    WorkInfo.State.RUNNING -> JobState.RUNNING
    WorkInfo.State.SUCCEEDED -> JobState.SUCCEEDED
    WorkInfo.State.FAILED -> JobState.FAILED
    WorkInfo.State.BLOCKED -> JobState.BLOCKED
    WorkInfo.State.CANCELLED -> JobState.CANCELLED
}
