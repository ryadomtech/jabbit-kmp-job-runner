package tech.ryadom.jabbit.internal

internal interface JobRecordStorage {

    suspend fun load(): List<JobRecord>

    suspend fun save(records: List<JobRecord>)
}

internal data class WakeUpPlan(
    val atMillis: Long,
    val requiresNetwork: Boolean,
    val requiresPower: Boolean
)

internal fun interface WakeUpPlanner {

    fun planWakeUp(plan: WakeUpPlan?)
}
