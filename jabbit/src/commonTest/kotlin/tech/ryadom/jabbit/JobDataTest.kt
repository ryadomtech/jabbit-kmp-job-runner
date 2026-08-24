package tech.ryadom.jabbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobDataTest {

    @Test
    fun keepsEverySupportedType() {
        val data = jobData {
            putBoolean("bool", true)
            putInt("int", 42)
            putLong("long", 1L shl 40)
            putFloat("float", 1.5f)
            putDouble("double", 2.5)
            putString("string", "hello")
            putStringList("list", listOf("a", "b"))
        }

        assertEquals(true, data.getBoolean("bool"))
        assertEquals(42, data.getInt("int"))
        assertEquals(1L shl 40, data.getLong("long"))
        assertEquals(1.5f, data.getFloat("float"))
        assertEquals(2.5, data.getDouble("double"))
        assertEquals("hello", data.getString("string"))
        assertEquals(listOf("a", "b"), data.getStringList("list"))
        assertEquals(7, data.size)
    }

    @Test
    fun returnsNullForMissingAndMistypedKeys() {
        val data = jobDataOf("count" to 1)

        assertNull(data.getString("count"))
        assertNull(data.getInt("missing"))
        assertEquals("fallback", data.getString("count", "fallback"))
        assertEquals(7, data.getInt("missing", 7))
    }

    @Test
    fun ignoresNullValuesAndRejectsUnsupportedOnes() {
        val data = jobDataOf("present" to "yes", "absent" to null)

        assertEquals(1, data.size)
        assertTrue("present" in data)
        assertFailsWith<IllegalArgumentException> { jobDataOf("bad" to JobDataTest()) }
        assertFailsWith<IllegalArgumentException> { jobDataOf("bad" to listOf(1, 2)) }
    }

    @Test
    fun mergesWithRightHandSideWinning() {
        val merged = jobDataOf("a" to 1, "b" to 2) + jobDataOf("b" to 3, "c" to 4)

        assertEquals(1, merged.getInt("a"))
        assertEquals(3, merged.getInt("b"))
        assertEquals(4, merged.getInt("c"))
    }

    @Test
    fun comparesByContent() {
        assertEquals(jobDataOf("a" to listOf("x")), jobDataOf("a" to listOf("x")))
        assertEquals(JobData.EMPTY, jobData { })
    }
}
