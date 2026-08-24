package tech.ryadom.jabbit.internal

import kotlin.random.Random

private const val HEX = "0123456789abcdef"

internal fun randomUuidString(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()

    val builder = StringBuilder(36)
    for (index in bytes.indices) {
        if (index == 4 || index == 6 || index == 8 || index == 10) {
            builder.append('-')
        }
        val value = bytes[index].toInt() and 0xFF
        builder.append(HEX[value ushr 4])
        builder.append(HEX[value and 0x0F])
    }
    return builder.toString()
}
