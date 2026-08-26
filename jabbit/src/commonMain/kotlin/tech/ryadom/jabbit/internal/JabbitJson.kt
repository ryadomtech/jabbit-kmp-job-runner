package tech.ryadom.jabbit.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

internal val JabbitJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun <T> encodePayload(serializer: KSerializer<T>, value: T): String =
    JabbitJson.encodeToString(serializer, value)

internal fun <T> decodePayload(serializer: KSerializer<T>, encoded: String?): T? {
    if (encoded == null) return null
    return try {
        JabbitJson.decodeFromString(serializer, encoded)
    } catch (error: Throwable) {
        null
    }
}
