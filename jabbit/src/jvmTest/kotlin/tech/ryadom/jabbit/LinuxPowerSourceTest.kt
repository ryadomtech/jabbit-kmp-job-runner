package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.DesktopPowerSource
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxPowerSourceTest {

    @Test
    fun readsALaptopOnMainsPower() {
        val root = sysfs(
            "AC" to mapOf("type" to "Mains", "online" to "1"),
            "BAT0" to mapOf("type" to "Battery", "capacity" to "80", "status" to "Charging")
        )

        val status = DesktopPowerSource.readLinux(root)

        assertEquals(true, status.charging)
        assertEquals(0.8f, status.level)
    }

    @Test
    fun readsALaptopOnBattery() {
        val root = sysfs(
            "AC" to mapOf("type" to "Mains", "online" to "0"),
            "BAT0" to mapOf("type" to "Battery", "capacity" to "42", "status" to "Discharging")
        )

        val status = DesktopPowerSource.readLinux(root)

        assertEquals(false, status.charging)
        assertEquals(0.42f, status.level)
    }

    @Test
    fun readsADesktopWithoutABattery() {
        val root = sysfs("AC" to mapOf("type" to "Mains", "online" to "1"))

        val status = DesktopPowerSource.readLinux(root)

        assertEquals(true, status.charging)
        assertNull(status.level)
    }

    @Test
    fun tellsNothingWhenTheKernelExposesNothing() {
        val status = DesktopPowerSource.readLinux(createTempDirectory("power").toFile())

        assertNull(status.charging)
        assertNull(status.level)
    }

    private fun sysfs(vararg supplies: Pair<String, Map<String, String>>): File {
        val root = createTempDirectory("power").toFile()

        supplies.forEach { (name, files) ->
            val directory = File(root, name).also { it.mkdirs() }
            files.forEach { (file, content) -> File(directory, file).writeText("$content\n") }
        }

        return root
    }
}
