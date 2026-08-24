package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.JabbitStorage
import tech.ryadom.jabbit.error

internal class JsonJobRecordStorage(
    private val storage: JabbitStorage,
    private val logger: JabbitLogger
) : JobRecordStorage {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun load(): List<JobRecord> {
        val raw = try {
            storage.read()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Failed to read persisted jobs", error)
            null
        } ?: return emptyList()

        return try {
            json.decodeFromString(recordListSerializer, raw)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Failed to parse persisted jobs, starting from an empty queue", error)
            emptyList()
        }
    }

    override suspend fun save(records: List<JobRecord>) {
        try {
            storage.write(json.encodeToString(recordListSerializer, records))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.error("Failed to persist jobs", error)
        }
    }

    private companion object {

        val recordListSerializer = kotlinx.serialization.builtins.ListSerializer(
            JobRecord.serializer()
        )
    }
}
