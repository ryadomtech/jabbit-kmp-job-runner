package tech.ryadom.jabbit.internal

internal expect fun currentTimeMillis(): Long

internal fun interface JabbitClock {
    fun nowMillis(): Long
}

internal val SystemClock: JabbitClock = JabbitClock { currentTimeMillis() }
