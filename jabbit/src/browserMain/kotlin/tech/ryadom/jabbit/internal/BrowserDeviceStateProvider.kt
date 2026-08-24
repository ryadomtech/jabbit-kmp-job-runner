package tech.ryadom.jabbit.internal

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import tech.ryadom.jabbit.JabbitBrowserOptions

private const val STORAGE_ESTIMATE_TTL_MILLIS = 30_000L

internal class BrowserDeviceStateProvider(
    private val options: JabbitBrowserOptions,
    private val clock: JabbitClock = SystemClock
) : DeviceStateProvider {

    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var charging: Boolean? = null

    private var batteryLevel: Double? = null

    private var freeStorageBytes: Long? = null

    private var storageEstimatedAt: Long = 0L

    override val changes: Flow<DeviceState> = signals.map { current() }

    fun start() {
        listOf("online", "offline").forEach { type ->
            addGlobalListener(type) { signals.tryEmit(Unit) }
        }

        addDocumentListener("visibilitychange") { signals.tryEmit(Unit) }

        readBatteryStatus(
            onStatus = { isCharging, level ->
                charging = isCharging
                batteryLevel = level
                signals.tryEmit(Unit)
            },
            onUnavailable = {
                charging = null
                batteryLevel = null
            }
        )
    }

    override suspend fun current(): DeviceState {
        refreshStorageEstimate()
        val level = batteryLevel
        val isCharging = charging

        return DeviceState(
            networkConnected = isOnline(),
            networkMetered = meteredState() == 1,
            networkRoaming = false,
            charging = isCharging ?: true,
            batteryNotLow = isCharging == true ||
                    level == null ||
                    level > options.lowBatteryThreshold,
            storageNotLow = freeStorageBytes?.let { it > options.lowStorageThresholdBytes } ?: true,
            deviceIdle = isServiceWorkerScope() || isDocumentHidden()
        )
    }

    private fun refreshStorageEstimate() {
        val now = clock.nowMillis()
        if (now - storageEstimatedAt < STORAGE_ESTIMATE_TTL_MILLIS) return
        storageEstimatedAt = now

        readStorageEstimate(
            onEstimate = { quota, usage ->
                freeStorageBytes = (quota - usage).toLong()
            },
            onUnavailable = {
                freeStorageBytes = null
            }
        )
    }
}
