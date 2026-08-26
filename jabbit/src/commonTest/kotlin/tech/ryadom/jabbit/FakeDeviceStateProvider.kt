package tech.ryadom.jabbit

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import tech.ryadom.jabbit.internal.DeviceState
import tech.ryadom.jabbit.internal.DeviceStateProvider

internal class FakeDeviceStateProvider(initial: DeviceState = DeviceState()) : DeviceStateProvider {

    private val state = MutableStateFlow(initial)

    override val changes: Flow<DeviceState> = state

    override suspend fun current(): DeviceState = state.value

    fun update(block: DeviceState.() -> DeviceState) {
        state.value = state.value.block()
    }
}
