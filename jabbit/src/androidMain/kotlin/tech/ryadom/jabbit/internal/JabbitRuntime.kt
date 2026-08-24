package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.JabbitConfiguration

internal object JabbitRuntime {

    @Volatile
    private var installed: JabbitConfiguration? = null

    val configuration: JabbitConfiguration?
        get() = installed

    fun install(configuration: JabbitConfiguration) {
        installed = configuration
    }
}
