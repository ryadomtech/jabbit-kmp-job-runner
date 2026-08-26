package tech.ryadom.jabbit.internal

import java.io.File
import java.util.concurrent.TimeUnit

internal data class PowerStatus(val charging: Boolean? = null, val level: Float? = null)

internal object DesktopPowerSource {

    private const val LINUX_POWER_SUPPLY = "/sys/class/power_supply"

    private const val COMMAND_TIMEOUT_SECONDS = 2L

    private val batteryLevel = Regex("(\\d{1,3})%")

    fun read(): PowerStatus {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return try {
            when {
                os.contains("linux") -> readLinux(File(LINUX_POWER_SUPPLY))
                os.contains("mac") || os.contains("darwin") -> readMacOs()
                else -> PowerStatus()
            }
        } catch (_: Throwable) {
            PowerStatus()
        }
    }

    fun readLinux(root: File): PowerStatus {
        val supplies = root.listFiles().orEmpty()
        if (supplies.isEmpty()) return PowerStatus()

        var charging: Boolean? = null
        var level: Float? = null

        supplies.forEach { supply ->
            when (supply.child("type")?.lowercase()) {
                "mains" -> if (supply.child("online") == "1") charging = true

                "battery" -> {
                    supply.child("capacity")?.toIntOrNull()?.let { level = it / 100f }
                    when (supply.child("status")?.lowercase()) {
                        "charging", "full" -> charging = true
                        "discharging" -> if (charging == null) charging = false
                    }
                }
            }
        }

        if (charging == null && level != null) charging = false
        return PowerStatus(charging = charging, level = level)
    }

    private fun readMacOs(): PowerStatus {
        val output = runCommand("pmset", "-g", "batt") ?: return PowerStatus()
        val charging = when {
            output.contains("'AC Power'") -> true
            output.contains("'Battery Power'") -> false
            else -> null
        }

        val level = batteryLevel.find(output)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.let { it / 100f }

        return PowerStatus(charging = charging, level = level)
    }

    private fun File.child(name: String): String? {
        val file = File(this, name)
        return if (file.canRead()) {
            file.readText()
                .trim()
                .takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    private fun runCommand(vararg command: String): String? {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()

        return try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
