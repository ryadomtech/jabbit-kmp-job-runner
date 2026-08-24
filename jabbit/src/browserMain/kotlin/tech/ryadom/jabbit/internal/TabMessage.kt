package tech.ryadom.jabbit.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import tech.ryadom.jabbit.ExistingJobPolicy
import tech.ryadom.jabbit.ExistingPeriodicJobPolicy

@Serializable
internal sealed class TabMessage {

    @Serializable
    @SerialName("requestState")
    internal data object RequestState : TabMessage()

    @Serializable
    @SerialName("state")
    internal data class State(val records: List<JobRecord>) : TabMessage()

    @Serializable
    @SerialName("enqueue")
    internal data class Enqueue(val records: List<JobRecord>) : TabMessage()

    @Serializable
    @SerialName("enqueueUnique")
    internal data class EnqueueUnique(
        val uniqueName: String,
        val policy: ExistingJobPolicy,
        val record: JobRecord
    ) : TabMessage()

    @Serializable
    @SerialName("enqueueUniquePeriodic")
    internal data class EnqueueUniquePeriodic(
        val uniqueName: String,
        val policy: ExistingPeriodicJobPolicy,
        val record: JobRecord
    ) : TabMessage()

    @Serializable
    @SerialName("cancelJob")
    internal data class CancelJob(val id: String) : TabMessage()

    @Serializable
    @SerialName("cancelByTag")
    internal data class CancelByTag(val tag: String) : TabMessage()

    @Serializable
    @SerialName("cancelUnique")
    internal data class CancelUnique(val uniqueName: String) : TabMessage()

    @Serializable
    @SerialName("cancelAll")
    internal data object CancelAll : TabMessage()

    @Serializable
    @SerialName("prune")
    internal data object Prune : TabMessage()
}
