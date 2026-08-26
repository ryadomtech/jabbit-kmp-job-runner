package tech.ryadom.jabbit.internal

import android.content.Context
import tech.ryadom.jabbit.JabbitConfiguration

internal object JabbitRuntime {

    @Volatile
    private var installed: JabbitConfiguration? = null

    @Volatile
    private var applicationContext: Context? = null

    val context: Context?
        get() = applicationContext

    fun installContext(context: Context) {
        applicationContext = context
    }

    val configuration: JabbitConfiguration?
        get() = installed

    fun install(configuration: JabbitConfiguration) {
        installed = configuration
    }
}
