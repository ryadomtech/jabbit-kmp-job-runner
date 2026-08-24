package tech.ryadom.jabbit

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserStorageTest {

    @Test
    fun indexedDbRoundTripsTheQueue() = runTest {
        val storage = IndexedDbJabbitStorage(databaseName = "jabbit-test-${Random.nextInt()}")

        assertNull(storage.read())

        storage.write("""[{"id":"a"}]""")
        assertEquals("""[{"id":"a"}]""", storage.read())

        storage.write("replaced")
        assertEquals("replaced", storage.read())
    }

    @Test
    fun localStorageRoundTripsTheQueue() = runTest {
        val storage = LocalStorageJabbitStorage(key = "jabbit-test-${Random.nextInt()}")

        assertNull(storage.read())

        storage.write("stored")
        assertEquals("stored", storage.read())
    }
}
