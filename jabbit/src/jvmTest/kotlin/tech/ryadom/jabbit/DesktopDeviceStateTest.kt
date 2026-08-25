package tech.ryadom.jabbit

import kotlinx.coroutines.test.runTest
import tech.ryadom.jabbit.internal.DesktopDeviceStateProvider
import tech.ryadom.jabbit.internal.DesktopPowerSource
import tech.ryadom.jabbit.internal.isSatisfiedBy
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopDeviceStateTest {

    private fun options(readPower: Boolean = true): JabbitDesktopOptions = JabbitDesktopOptions(
        applicationName = "jabbit-test",
        storageDirectory = createTempDirectory("jabbit"),
        readPowerSource = readPower
    )

    @Test
    fun readsTheMachineWithoutFailing() = runTest {
        val state = DesktopDeviceStateProvider(options()).current()

        assertTrue(Constraints.NONE.isSatisfiedBy(state))
        assertTrue(constraints { requiresStorageNotLow = true }.isSatisfiedBy(state))
    }

    @Test
    fun unavailableSensorsDoNotBlockJobs() = runTest {
        val state = DesktopDeviceStateProvider(options(readPower = false)).current()

        assertTrue(constraints { requiresCharging = true }.isSatisfiedBy(state))
        assertTrue(constraints { requiresBatteryNotLow = true }.isSatisfiedBy(state))
        assertTrue(constraints { requiresDeviceIdle = true }.isSatisfiedBy(state))
    }

    @Test
    fun readsThePowerSourceOfThisMachine() {
        val status = DesktopPowerSource.read()

        status.level?.let { assertTrue(it in 0f..1f, "battery level out of range: $it") }
    }
}
