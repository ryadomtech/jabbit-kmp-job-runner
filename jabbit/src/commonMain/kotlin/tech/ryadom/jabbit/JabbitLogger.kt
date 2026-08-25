package tech.ryadom.jabbit

/**
 * Sink for the diagnostic output of the scheduler.
 *
 * Install one through [JabbitConfiguration.Builder.logger] to route Jabbit into the logging stack
 * of the app.
 */
public fun interface JabbitLogger {

    /**
     * Records a single message.
     */
    public fun log(level: Level, message: String, error: Throwable?)

    /**
     * Severity of a logged message.
     */
    public enum class Level { DEBUG, INFO, WARN, ERROR }

    public companion object {

        /**
         * Discards everything. This is the default.
         */
        public val None: JabbitLogger = JabbitLogger { _, _, _ -> }

        /**
         * Prints to the platform console.
         */
        public val Console: JabbitLogger = JabbitLogger { level, message, error ->
            println("[Jabbit/$level] $message")
            error?.let { println("[Jabbit/$level] ${it.stackTraceToString()}") }
        }
    }
}

internal fun JabbitLogger.debug(message: String) = log(JabbitLogger.Level.DEBUG, message, null)

internal fun JabbitLogger.info(message: String) = log(JabbitLogger.Level.INFO, message, null)

internal fun JabbitLogger.warn(message: String, error: Throwable? = null) =
    log(JabbitLogger.Level.WARN, message, error)

internal fun JabbitLogger.error(message: String, error: Throwable? = null) =
    log(JabbitLogger.Level.ERROR, message, error)
