package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.JobDataValue

/**
 * Immutable, typed key/value payload that is handed to a worker as input and returned from it as
 * output or progress.
 *
 * Supported value types are [Boolean], [Int], [Long], [Float], [Double], [String] and
 * `List<String>`. Payloads are persisted by the platform scheduler, so they must stay small:
 * Android rejects serialized payloads larger than 10 KB. Anything bigger belongs in a file or a
 * database, with only the key or path passed through [JobData].
 *
 * Use [jobData] or [jobDataOf] to create instances.
 */
public class JobData internal constructor(internal val values: Map<String, JobDataValue>) {

    /**
     * All keys contained in this payload.
     */
    public val keys: Set<String> get() = values.keys

    /**
     * Number of entries in this payload.
     */
    public val size: Int get() = values.size

    /**
     * Returns `true` when this payload holds no entries.
     */
    public fun isEmpty(): Boolean = values.isEmpty()

    /**
     * Returns `true` when this payload holds at least one entry.
     */
    public fun isNotEmpty(): Boolean = values.isNotEmpty()

    /**
     * Returns `true` when [key] is present, regardless of its type.
     */
    public operator fun contains(key: String): Boolean = values.containsKey(key)

    /**
     * Returns the [Boolean] stored under [key], or `null` when absent or of another type.
     */
    public fun getBoolean(key: String): Boolean? =
        (values[key] as? JobDataValue.BooleanValue)?.value

    /**
     * Returns the [Boolean] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        getBoolean(key) ?: defaultValue

    /**
     * Returns the [Int] stored under [key], or `null` when absent or of another type.
     */
    public fun getInt(key: String): Int? = (values[key] as? JobDataValue.IntValue)?.value

    /**
     * Returns the [Int] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getInt(key: String, defaultValue: Int): Int = getInt(key) ?: defaultValue

    /**
     * Returns the [Long] stored under [key], or `null` when absent or of another type.
     */
    public fun getLong(key: String): Long? = (values[key] as? JobDataValue.LongValue)?.value

    /**
     * Returns the [Long] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getLong(key: String, defaultValue: Long): Long = getLong(key) ?: defaultValue

    /**
     * Returns the [Float] stored under [key], or `null` when absent or of another type.
     */
    public fun getFloat(key: String): Float? = (values[key] as? JobDataValue.FloatValue)?.value

    /**
     * Returns the [Float] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getFloat(key: String, defaultValue: Float): Float = getFloat(key) ?: defaultValue

    /**
     * Returns the [Double] stored under [key], or `null` when absent or of another type.
     */
    public fun getDouble(key: String): Double? = (values[key] as? JobDataValue.DoubleValue)?.value

    /**
     * Returns the [Double] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getDouble(key: String, defaultValue: Double): Double = getDouble(key) ?: defaultValue

    /**
     * Returns the [String] stored under [key], or `null` when absent or of another type.
     */
    public fun getString(key: String): String? = (values[key] as? JobDataValue.StringValue)?.value

    /**
     * Returns the [String] stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getString(key: String, defaultValue: String): String = getString(key) ?: defaultValue

    /**
     * Returns the string list stored under [key], or `null` when absent or of another type.
     */
    public fun getStringList(key: String): List<String>? =
        (values[key] as? JobDataValue.StringListValue)?.value

    /**
     * Returns the string list stored under [key], or [defaultValue] when absent or of another type.
     */
    public fun getStringList(key: String, defaultValue: List<String>): List<String> =
        getStringList(key) ?: defaultValue

    /**
     * Returns this payload as a plain map of boxed values.
     */
    public fun toMap(): Map<String, Any> = values.mapValues { (_, value) -> value.raw }

    /**
     * Returns a new payload where entries of [other] win over entries of this payload.
     */
    public operator fun plus(other: JobData): JobData = JobData(values + other.values)

    override fun equals(other: Any?): Boolean = this === other ||
        (other is JobData && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "JobData(${toMap()})"

    public companion object {

        /**
         * The empty payload.
         */
        public val EMPTY: JobData = JobData(emptyMap())
    }
}

/**
 * Builds a [JobData] payload.
 *
 * ```
 * val input = jobData {
 *     putString("url", "https://example.com/report.pdf")
 *     putBoolean("wifiOnly", true)
 * }
 * ```
 */
public fun jobData(builder: JobDataBuilder.() -> Unit): JobData =
    JobDataBuilder().apply(builder).build()

/**
 * Builds a [JobData] payload from pairs, ignoring entries whose value is `null`.
 *
 * ```
 * val input = jobDataOf("userId" to 42, "force" to true)
 * ```
 *
 * @throws IllegalArgumentException when a value is of an unsupported type.
 */
public fun jobDataOf(vararg pairs: Pair<String, Any?>): JobData {
    val builder = JobDataBuilder()
    for ((key, value) in pairs) {
        builder.putAny(key, value)
    }
    return builder.build()
}

/**
 * Mutable builder for [JobData].
 */
public class JobDataBuilder {

    private val values = mutableMapOf<String, JobDataValue>()

    /**
     * Stores a [Boolean] under [key].
     */
    public fun putBoolean(key: String, value: Boolean): JobDataBuilder = apply {
        values[key] = JobDataValue.BooleanValue(value)
    }

    /**
     * Stores an [Int] under [key].
     */
    public fun putInt(key: String, value: Int): JobDataBuilder = apply {
        values[key] = JobDataValue.IntValue(value)
    }

    /**
     * Stores a [Long] under [key].
     */
    public fun putLong(key: String, value: Long): JobDataBuilder = apply {
        values[key] = JobDataValue.LongValue(value)
    }

    /**
     * Stores a [Float] under [key].
     */
    public fun putFloat(key: String, value: Float): JobDataBuilder = apply {
        values[key] = JobDataValue.FloatValue(value)
    }

    /**
     * Stores a [Double] under [key].
     */
    public fun putDouble(key: String, value: Double): JobDataBuilder = apply {
        values[key] = JobDataValue.DoubleValue(value)
    }

    /**
     * Stores a [String] under [key].
     */
    public fun putString(key: String, value: String): JobDataBuilder = apply {
        values[key] = JobDataValue.StringValue(value)
    }

    /**
     * Stores a list of strings under [key].
     */
    public fun putStringList(key: String, value: List<String>): JobDataBuilder = apply {
        values[key] = JobDataValue.StringListValue(value.toList())
    }

    /**
     * Copies every entry of [data] into this builder.
     */
    public fun putAll(data: JobData): JobDataBuilder = apply {
        values.putAll(data.values)
    }

    /**
     * Removes the entry stored under [key].
     */
    public fun remove(key: String): JobDataBuilder = apply {
        values.remove(key)
    }

    /**
     * Builds the payload.
     */
    public fun build(): JobData = JobData(values.toMap())

    internal fun putAny(key: String, value: Any?) {
        when (value) {
            null -> Unit

            is Boolean -> putBoolean(key, value)

            is Int -> putInt(key, value)

            is Long -> putLong(key, value)

            is Float -> putFloat(key, value)

            is Double -> putDouble(key, value)

            is String -> putString(key, value)

            is List<*> -> putStringList(
                key = key,
                value = value.map {
                    it as? String
                        ?: throw IllegalArgumentException(
                            "Only List<String> is supported, got a list " +
                                "containing $it for key '$key'"
                        )
                }
            )

            else -> throw IllegalArgumentException(
                "Unsupported JobData value type ${value::class.simpleName} for key '$key'"
            )
        }
    }
}
