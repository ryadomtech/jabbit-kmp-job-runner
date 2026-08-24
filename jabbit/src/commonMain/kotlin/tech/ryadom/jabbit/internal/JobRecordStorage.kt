package tech.ryadom.jabbit.internal

internal interface JobRecordStorage {

    suspend fun load(): List<JobRecord>

    suspend fun save(records: List<JobRecord>)
}

internal class InMemoryJobRecordStorage(
    initial: List<JobRecord> = emptyList()
) : JobRecordStorage {

    private var records: List<JobRecord> = initial

    override suspend fun load(): List<JobRecord> = records

    override suspend fun save(records: List<JobRecord>) {
        this.records = records
    }
}

internal data class WakeUpPlan(
    val atMillis: Long,
    val requiresNetwork: Boolean,
    val requiresPower: Boolean
)

internal fun interface WakeUpPlanner {

    fun planWakeUp(plan: WakeUpPlan?)
}
