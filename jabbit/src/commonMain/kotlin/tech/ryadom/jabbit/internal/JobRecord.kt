package tech.ryadom.jabbit.internal

import kotlinx.serialization.Serializable
import tech.ryadom.jabbit.BackoffPolicy
import tech.ryadom.jabbit.Constraints
import tech.ryadom.jabbit.JobId
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobProgress
import tech.ryadom.jabbit.JobRequest
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.PeriodicJobRequest

@Serializable
internal data class JobRecord(
    val id: String,
    val typeName: String,
    val uniqueName: String? = null,
    val tags: Set<String> = emptySet(),
    val encodedInput: String = "null",
    @Serializable(with = ConstraintsSerializer::class)
    val constraints: Constraints = Constraints.NONE,
    val backoffPolicy: BackoffPolicy = BackoffPolicy.EXPONENTIAL,
    val backoffDelayMillis: Long = BackoffPolicy.DEFAULT_DELAY.inWholeMilliseconds,
    val maxAttempts: Int = JobRequest.UNLIMITED_ATTEMPTS,
    val initialDelayMillis: Long = 0L,
    val repeatIntervalMillis: Long? = null,
    val flexIntervalMillis: Long? = null,
    val state: JobState = JobState.ENQUEUED,
    val runAttemptCount: Int = 0,
    val periodStartAtMillis: Long? = null,
    val earliestRunAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val finishedAtMillis: Long? = null,
    val encodedOutput: String? = null,
    val failureReason: String? = null,
    val progress: JobProgress? = null
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
        typeName = typeName,
        uniqueName = uniqueName,
        tags = tags,
        encodedInput = encodedInput,
        constraints = constraints,
        backoffPolicy = backoffPolicy,
        backoffDelayMillis = backoffDelay.inWholeMilliseconds,
        maxAttempts = maxAttempts,
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
    typeName = other.typeName,
    tags = other.tags,
    encodedInput = other.encodedInput,
    constraints = other.constraints,
    backoffPolicy = other.backoffPolicy,
    backoffDelayMillis = other.backoffDelayMillis,
    maxAttempts = other.maxAttempts,
    initialDelayMillis = other.initialDelayMillis,
    repeatIntervalMillis = other.repeatIntervalMillis,
    flexIntervalMillis = other.flexIntervalMillis
)

internal fun JobRecord.recovered(nowMillis: Long): JobRecord = when (state) {
    JobState.RUNNING -> copy(
        state = JobState.ENQUEUED,
        runAttemptCount = runAttemptCount + 1,
        earliestRunAtMillis = nowMillis,
        progress = null
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
        progress = null
    )
}

internal fun JobRecord.toJobInfo(): JobInfo = JobInfo(
    id = JobId(id),
    typeName = typeName,
    state = state,
    tags = tags,
    uniqueName = uniqueName,
    progress = progress,
    failureReason = failureReason,
    runAttemptCount = runAttemptCount,
    nextScheduleTimeMillis = earliestRunAtMillis.takeIf { state == JobState.ENQUEUED },
    encodedOutput = encodedOutput
)
