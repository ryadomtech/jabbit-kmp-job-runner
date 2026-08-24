package tech.ryadom.jabbit.internal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed class JobDataValue {

    abstract val raw: Any

    @Serializable
    @SerialName("bool")
    internal data class BooleanValue(val value: Boolean) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("int")
    internal data class IntValue(val value: Int) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("long")
    internal data class LongValue(val value: Long) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("float")
    internal data class FloatValue(val value: Float) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("double")
    internal data class DoubleValue(val value: Double) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("string")
    internal data class StringValue(val value: String) : JobDataValue() {
        override val raw: Any get() = value
    }

    @Serializable
    @SerialName("stringList")
    internal data class StringListValue(val value: List<String>) : JobDataValue() {
        override val raw: Any get() = value
    }
}
