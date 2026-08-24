package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.WORKER_NAME_KEY
import tech.ryadom.jabbit.internal.toJobData
import tech.ryadom.jabbit.internal.toWorkData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkDataMappingTest {

    @Test
    fun roundTripsEverySupportedType() {
        val data = jobData {
            putBoolean("bool", true)
            putInt("int", 1)
            putLong("long", 2L)
            putFloat("float", 3.5f)
            putDouble("double", 4.5)
            putString("string", "five")
            putStringList("list", listOf("six", "seven"))
        }

        assertEquals(data, data.toWorkData().toJobData())
    }

    @Test
    fun ignoresKeysThatDoNotBelongToThePayload() {
        val workData = androidx.work.Data.Builder()
            .putString(WORKER_NAME_KEY, "sync")
            .putString("foreign", "value")
            .build()

        assertTrue(workData.toJobData().isEmpty())
    }

    @Test
    fun keepsPayloadKeysSeparateFromInternalOnes() {
        val workData = jobDataOf("workerName" to "not-the-worker")
            .toWorkData()

        assertEquals("not-the-worker", workData.toJobData().getString("workerName"))
        assertEquals(null, workData.getString(WORKER_NAME_KEY))
    }
}
