package tech.ryadom.jabbit.internal

import kotlinx.coroutines.flow.Flow
import tech.ryadom.jabbit.Constraints
import tech.ryadom.jabbit.NetworkType

internal data class DeviceState(
    val networkConnected: Boolean = true,
    val networkMetered: Boolean = false,
    val networkRoaming: Boolean = false,
    val charging: Boolean = true,
    val batteryNotLow: Boolean = true,
    val storageNotLow: Boolean = true,
    val deviceIdle: Boolean = true
)

internal interface DeviceStateProvider {

    suspend fun current(): DeviceState

    val changes: Flow<DeviceState>
}

internal fun Constraints.isSatisfiedBy(state: DeviceState): Boolean {
    val networkSatisfied = when (requiredNetworkType) {
        NetworkType.NOT_REQUIRED -> true
        NetworkType.CONNECTED -> state.networkConnected
        NetworkType.UNMETERED -> state.networkConnected && !state.networkMetered
        NetworkType.METERED -> state.networkConnected && state.networkMetered
        NetworkType.NOT_ROAMING -> state.networkConnected && !state.networkRoaming
    }

    return networkSatisfied &&
            (!requiresCharging || state.charging) &&
            (!requiresBatteryNotLow || state.batteryNotLow) &&
            (!requiresStorageNotLow || state.storageNotLow) &&
            (!requiresDeviceIdle || state.deviceIdle)
}
