package tech.ryadom.jabbit

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Describes one kind of job: the name it is persisted under, and the payloads it accepts and
 * produces.
 *
 * Declare one per worker and share it between the code that registers the worker and the code that
 * enqueues jobs, so the compiler checks that both agree on the payload:
 *
 * ```
 * @Serializable
 * data class SyncInput(val since: Long)
 *
 * @Serializable
 * data class SyncOutput(val items: Int)
 *
 * val SyncJob = jobType<SyncInput, SyncOutput>("sync")
 * ```
 *
 * [name] is persisted with every enqueued job, so it has to stay stable across releases — unlike a
 * class name, it survives obfuscation and refactoring.
 *
 * Use [Unit] for a job that needs no input, no output, or neither.
 */
public class JobType<I, O> @PublishedApi internal constructor(

    /**
     * Stable name this kind of job is persisted under.
     */
    public val name: String,

    internal val inputSerializer: KSerializer<I>,

    internal val outputSerializer: KSerializer<O>
) {

    init {
        require(name.isNotBlank()) { "Job type name must not be blank" }
        require(!name.startsWith(RESERVED_TAG_PREFIX)) {
            "Names starting with '$RESERVED_TAG_PREFIX' are reserved by Jabbit, got '$name'"
        }
    }

    override fun toString(): String = "JobType($name)"
}

/**
 * Declares a [JobType] named [name], taking [I] and producing [O].
 *
 * Both types have to be serializable with `kotlinx.serialization`, because a job outlives the
 * process that enqueued it.
 */
public inline fun <reified I, reified O> jobType(name: String): JobType<I, O> =
    JobType(name, serializer<I>(), serializer<O>())
