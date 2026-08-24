package tech.ryadom.jabbit

import kotlinx.coroutines.test.runTest
import tech.ryadom.jabbit.internal.BrowserDeviceStateProvider
import tech.ryadom.jabbit.internal.isSatisfiedBy
import kotlin.test.Test
import kotlin.test.assertTrue

class BrowserDeviceStateTest {

    @Test
    fun readsDeviceStateWithoutFailing() = runTest {
        val provider = BrowserDeviceStateProvider(JabbitBrowserOptions())
        provider.start()

        val state = provider.current()

        assertTrue(state.networkConnected, "a test browser is expected to be online")
        assertTrue(Constraints.NONE.isSatisfiedBy(state))
        assertTrue(
            constraints { requiredNetworkType = NetworkType.CONNECTED }.isSatisfiedBy(state)
        )
    }

    @Test
    fun unavailableSensorsDoNotBlockJobs() = runTest {
        val provider = BrowserDeviceStateProvider(JabbitBrowserOptions())
        provider.start()

        val state = provider.current()

        assertTrue(constraints { requiresBatteryNotLow = true }.isSatisfiedBy(state))
        assertTrue(constraints { requiresStorageNotLow = true }.isSatisfiedBy(state))
    }
}
