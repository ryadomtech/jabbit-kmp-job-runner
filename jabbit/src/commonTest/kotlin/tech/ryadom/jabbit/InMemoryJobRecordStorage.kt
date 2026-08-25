package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JobRecord
import tech.ryadom.jabbit.internal.JobRecordStorage

internal class InMemoryJobRecordStorage(initial: List<JobRecord> = emptyList()) : JobRecordStorage {

    private var records: List<JobRecord> = initial

    override suspend fun load(): List<JobRecord> = records

    override suspend fun save(records: List<JobRecord>) {
        this.records = records
    }
}
