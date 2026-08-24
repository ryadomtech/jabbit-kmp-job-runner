package tech.ryadom.jabbit.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import tech.ryadom.jabbit.Constraints
import tech.ryadom.jabbit.JobData
import tech.ryadom.jabbit.NetworkType

internal object JobDataSerializer : KSerializer<JobData> {

    private val delegate = MapSerializer(String.serializer(), JobDataValue.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: JobData) {
        delegate.serialize(encoder, value.values)
    }

    override fun deserialize(decoder: Decoder): JobData = JobData(delegate.deserialize(decoder))
}

@Serializable
private class ConstraintsSurrogate(
    val network: NetworkType = NetworkType.NOT_REQUIRED,
    val charging: Boolean = false,
    val batteryNotLow: Boolean = false,
    val storageNotLow: Boolean = false,
    val deviceIdle: Boolean = false
)

internal object ConstraintsSerializer : KSerializer<Constraints> {

    private val delegate = ConstraintsSurrogate.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Constraints) {
        delegate.serialize(
            encoder,
            ConstraintsSurrogate(
                network = value.requiredNetworkType,
                charging = value.requiresCharging,
                batteryNotLow = value.requiresBatteryNotLow,
                storageNotLow = value.requiresStorageNotLow,
                deviceIdle = value.requiresDeviceIdle
            )
        )
    }

    override fun deserialize(decoder: Decoder): Constraints {
        val surrogate = delegate.deserialize(decoder)
        return Constraints(
            requiredNetworkType = surrogate.network,
            requiresCharging = surrogate.charging,
            requiresBatteryNotLow = surrogate.batteryNotLow,
            requiresStorageNotLow = surrogate.storageNotLow,
            requiresDeviceIdle = surrogate.deviceIdle
        )
    }
}
