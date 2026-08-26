package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.Jabbit
import tech.ryadom.jabbit.JabbitScope

internal actual fun createPlatformJabbit(jabbitScope: JabbitScope): Jabbit {
    val context = checkNotNull(JabbitRuntime.context) {
        "Jabbit did not receive an application context. Its initialization provider was most " +
            "likely removed from the merged manifest."
    }

    val configuration = jabbitScope.buildConfiguration()
    JabbitRuntime.install(configuration)

    return WorkManagerJabbit(context, configuration)
}
