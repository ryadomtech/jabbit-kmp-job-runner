package tech.ryadom.jabbit

import kotlinx.coroutines.test.runTest
import tech.ryadom.jabbit.internal.JobRecord
import tech.ryadom.jabbit.internal.JsonJobRecordStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonJobRecordStorageTest {

    private val record = JobRecord(
        id = "9d1a0a3e-0c1a-4d0a-9b1a-0c1a4d0a9b1a",
        typeName = "sync",
        uniqueName = "unique-sync",
        tags = setOf("a", "b"),
        encodedInput = """{"name":"world"}""",
        constraints = constraints {
            requiredNetworkType = NetworkType.UNMETERED
            requiresCharging = true
            requiresDeviceIdle = true
        },
        backoffPolicy = BackoffPolicy.LINEAR,
        backoffDelayMillis = 15_000,
        maxAttempts = 5,
        initialDelayMillis = 1_000,
        repeatIntervalMillis = 900_000,
        flexIntervalMillis = 300_000,
        state = JobState.ENQUEUED,
        runAttemptCount = 3,
        periodStartAtMillis = 10_000,
        earliestRunAtMillis = 20_000,
        createdAtMillis = 5_000,
        finishedAtMillis = null,
        encodedOutput = """{"items":7}""",
        failureReason = "the server said no",
        progress = JobProgress(fraction = 0.5f, message = "halfway")
    )

    @Test
    fun roundTripsEveryField() = runTest {
        val storage = FakeStorage()
        val subject = JsonJobRecordStorage(storage, JabbitLogger.None)

        subject.save(listOf(record))

        assertEquals(listOf(record), subject.load())
    }

    @Test
    fun startsEmptyWhenNothingWasStored() = runTest {
        assertTrue(JsonJobRecordStorage(FakeStorage(), JabbitLogger.None).load().isEmpty())
    }

    @Test
    fun startsEmptyWhenTheDocumentIsCorrupted() = runTest {
        val storage = FakeStorage().also { it.write("] not json [") }

        assertTrue(JsonJobRecordStorage(storage, JabbitLogger.None).load().isEmpty())
    }

    private class FakeStorage : JabbitStorage {

        private var value: String? = null

        override suspend fun read(): String? = value

        override suspend fun write(value: String) {
            this.value = value
        }
    }
}
