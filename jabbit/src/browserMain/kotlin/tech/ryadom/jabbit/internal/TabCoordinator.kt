package tech.ryadom.jabbit.internal

import kotlinx.serialization.json.Json
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.warn

internal class TabCoordinator(
    private val channelName: String,
    private val logger: JabbitLogger
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var handle: BroadcastHandle? = null

    val isAvailable: Boolean
        get() = handle != null

    fun start(onMessage: (TabMessage) -> Unit) {
        handle = openBroadcast(channelName) { raw ->
            val message = try {
                json.decodeFromString(TabMessage.serializer(), raw)
            } catch (error: Throwable) {
                logger.warn("Ignoring an unreadable message from another tab", error)
                null
            }
            message?.let(onMessage)
        }

        if (handle == null) {
            logger.warn("BroadcastChannel is unavailable, tabs will not share job state")
        }
    }

    fun send(message: TabMessage) {
        val channel = handle ?: return
        try {
            postBroadcast(channel, json.encodeToString(TabMessage.serializer(), message))
        } catch (error: Throwable) {
            logger.warn("Could not notify other tabs", error)
        }
    }
}
