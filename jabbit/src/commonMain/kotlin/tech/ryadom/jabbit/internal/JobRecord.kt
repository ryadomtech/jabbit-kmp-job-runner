package tech.ryadom.jabbit.internal

import kotlinx.serialization.Serializable
import tech.ryadom.jabbit.BackoffPolicy
import tech.ryadom.jabbit.Constraints
import tech.ryadom.jabbit.JobData
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.PeriodicJobRequest

@Serializable
internal data class JobRecord(
    val id: String,
    val workerName: String,
    val uniqueName: String? = null,
    val tags: Set<String> = emptySet(),
    @Serializable(with = JobDataSerializer::class)
    val inputData: JobData = JobData.EMPTY,
    @Serializable(with = ConstraintsSerializer::class)
    val constraints: Constraints = Constraints.NONE,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMillis: Long = BackoffPolicy.DEFAULT_DELAY.inWholeMilliseconds,
    val initialDelayMillis: Long = 0L,
    val repeatIntervalMillis: Long? = null,
    val flexIntervalMillis: Long? = null,
    val state: JobState = JobState.ENQUEUED,
    val runAttemptCount: Int = 0,
    val periodStartAtMillis: Long? = null,
    val earliestRunAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
    @Serializable(with = JobDataSerializer::class)
    val outputData: JobData = JobData.EMPTY,
    @Serializable(with = JobDataSerializer::class)
    val progress: JobData = JobData.EMPTY
) {

    val isPeriodic: Boolean get() = repeatIntervalMillis != null
}

internal fun JobRequest.toRecord(uniqueName: String?, nowMillis: Long): JobRecord {
    val periodic = this as? PeriodicJobRequest
    val periodStart = nowMillis + initialDelay.inWholeMilliseconds
    val flex = periodic?.flexInterval?.inWholeMilliseconds
    val interval = periodic?.repeatInterval?.inWholeMilliseconds

    return JobRecord(
        id = id.value,
        workerName = workerName,
        uniqueName = uniqueName,
        tags = tags,
        inputData = inputData,
        constraints = constraints,
        backoffPolicy = backoffPolicy,
        backoffDelayMillis = backoffDelay.inWholeMilliseconds,
        initialDelayMillis = initialDelay.inWholeMilliseconds,
        repeatIntervalMillis = interval,
        flexIntervalMillis = flex,
        state = JobState.ENQUEUED,
        runAttemptCount = 0,
        periodStartAtMillis = periodic?.let { periodStart },
        earliestRunAtMillis = if (interval != null && flex != null) {
            periodStart + (interval - flex)
        } else {
            periodStart
        },
        createdAtMillis = nowMillis
    )
}

internal fun JobRecord.withConfigurationOf(other: JobRecord): JobRecord = copy(
    workerName = other.workerName,
    tags = other.tags,
    inputData = other.inputData,
    constraints = other.constraints,
    backoffPolicy = other.backoffPolicy,
    backoffDelayMillis = other.backoffDelayMillis,
    initialDelayMillis = other.initialDelayMillis,
    repeatIntervalMillis = other.repeatIntervalMillis,
    flexIntervalMillis = other.flexIntervalMillis
)

internal fun JobRecord.recovered(nowMillis: Long): JobRecord = when (state) {
    JobState.RUNNING -> copy(
        state = JobState.ENQUEUED,
        runAttemptCount = runAttemptCount + 1,
        earliestRunAtMillis = nowMillis,
        progress = JobData.EMPTY
    )

    else -> this
}

internal fun JobRecord.nextPeriod(nowMillis: Long): JobRecord {
    val interval = repeatIntervalMillis ?: return this
    val flex = flexIntervalMillis ?: interval
    val previousStart = periodStartAtMillis ?: nowMillis
    val candidate = previousStart + interval
    val periodStart = if (candidate + interval <= nowMillis) nowMillis else candidate

    return copy(
        state = JobState.ENQUEUED,
        runAttemptCount = 0,
        periodStartAtMillis = periodStart,
        earliestRunAtMillis = periodStart + (interval - flex),
        finishedAtMillis = null,
        progress = JobData.EMPTY
    )
}

internal fun JobRecord.toJobInfo(): JobInfo = JobInfo(
    id = JobId(id),
    workerName = workerName,
    state = state,
    tags = tags,
    uniqueName = uniqueName,
    outputData = outputData,
    progress = progress,
    runAttemptCount = runAttemptCount,
    nextScheduleTimeMillis = earliestRunAtMillis.takeIf { state == JobState.ENQUEUED }
)
