package tech.ryadom.jabbit

import kotlinx.serialization.json.Json
import tech.ryadom.jabbit.internal.TabMessage
import tech.ryadom.jabbit.internal.toRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class TabMessageTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun carriesEveryCommandBetweenTabs() {
        val record = oneTimeJob(GreetJob, Greeting("world")) {
            addTag("tag")
        }.toRecord(uniqueName = "unique", nowMillis = 1_000)

        val messages = listOf(
            TabMessage.RequestState,
            TabMessage.State(listOf(record)),
            TabMessage.Enqueue(listOf(record)),
            TabMessage.EnqueueUnique("unique", ExistingJobPolicy.REPLACE, record),
            TabMessage.EnqueueUniquePeriodic(
                uniqueName = "unique",
                policy = ExistingPeriodicJobPolicy.UPDATE,
                record = record
            ),
            TabMessage.CancelJob(record.id),
            TabMessage.CancelByTag("tag"),
            TabMessage.CancelUnique("unique"),
            TabMessage.CancelAll,
            TabMessage.Prune
        )

        messages.forEach { message ->
            val encoded = json.encodeToString(TabMessage.serializer(), message)
            assertEquals(message, json.decodeFromString(TabMessage.serializer(), encoded))
        }
    }
}
