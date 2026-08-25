package tech.ryadom.jabbit

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileJabbitStorageTest {

    @Test
    fun returnsNothingWhenTheFileIsMissing() = runTest {
        val directory = createTempDirectory("jabbit")
        val storage = FileJabbitStorage(directory.resolve("jobs.json"))

        assertNull(storage.read())
    }

    @Test
    fun roundTripsAndReplacesTheDocument() = runTest {
        val directory = createTempDirectory("jabbit")
        val storage = FileJabbitStorage(directory.resolve("jobs.json"))

        storage.write("""[{"id":"a"}]""")
        assertEquals("""[{"id":"a"}]""", storage.read())

        storage.write("replaced")
        assertEquals("replaced", storage.read())
    }

    @Test
    fun createsMissingDirectoriesAndLeavesNoTemporaryFile() = runTest {
        val directory = createTempDirectory("jabbit").resolve("nested").resolve("deeper")
        val storage = FileJabbitStorage(directory.resolve("jobs.json"))

        storage.write("stored")

        assertEquals("stored", storage.read())
        val leftovers = Files.list(directory).use { files ->
            files.filter { it.toString().endsWith(".tmp") }.count()
        }

        assertEquals(0L, leftovers)
    }
}
