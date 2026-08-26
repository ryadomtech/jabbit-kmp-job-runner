package tech.ryadom.jabbit

import kotlinx.coroutines.CancellationException
import tech.ryadom.jabbit.internal.WorkerRegistration
import kotlin.time.Duration

internal class JabbitConfiguration(
    val registrations: Map<String, WorkerRegistration<*, *>>,
    val listeners: List<JabbitListener>,
    val logger: JabbitLogger,
    val maxConcurrentJobs: Int,
    val finishedJobRetention: Duration
) {

    fun registrationOf(typeName: String): WorkerRegistration<*, *>? = registrations[typeName]

    fun notify(event: (JabbitListener) -> Unit) {
        listeners.forEach { listener ->
            try {
                event(listener)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.error("A Jabbit listener threw and was skipped", error)
            }
        }
    }

    fun requireKnownType(typeName: String) {
        require(typeName in registrations) {
            "Job type '$typeName' is not registered. Known types: " +
                registrations.keys.sorted().joinToString()
        }
    }
}
