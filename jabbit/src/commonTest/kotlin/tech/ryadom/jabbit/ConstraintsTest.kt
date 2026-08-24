package tech.ryadom.jabbit

import tech.ryadom.jabbit.internal.DeviceState
import tech.ryadom.jabbit.internal.isSatisfiedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConstraintsTest {

    @Test
    fun noConstraintsAreAlwaysSatisfied() {
        val offline = DeviceState(
            networkConnected = false,
            charging = false,
            batteryNotLow = false,
            storageNotLow = false,
            deviceIdle = false
        )

        assertTrue(Constraints.NONE.isSatisfiedBy(offline))
    }

    @Test
    fun meteringIsHonoured() {
        val cellular = DeviceState(networkConnected = true, networkMetered = true)
        val wifi = DeviceState(networkConnected = true, networkMetered = false)

        assertTrue(Constraints(requiredNetworkType = NetworkType.CONNECTED).isSatisfiedBy(cellular))
        assertFalse(Constraints(requiredNetworkType = NetworkType.UNMETERED).isSatisfiedBy(cellular))
        assertTrue(Constraints(requiredNetworkType = NetworkType.UNMETERED).isSatisfiedBy(wifi))
        assertFalse(Constraints(requiredNetworkType = NetworkType.METERED).isSatisfiedBy(wifi))
    }

    @Test
    fun deviceConditionsAreHonoured() {
        val idleUnplugged =
            DeviceState(charging = false, batteryNotLow = false, storageNotLow = false)

        assertFalse(constraints { requiresCharging = true }.isSatisfiedBy(idleUnplugged))
        assertFalse(constraints { requiresBatteryNotLow = true }.isSatisfiedBy(idleUnplugged))
        assertFalse(constraints { requiresStorageNotLow = true }.isSatisfiedBy(idleUnplugged))
        assertTrue(constraints { requiresDeviceIdle = true }.isSatisfiedBy(idleUnplugged))
    }

    @Test
    fun builderMatchesTheDsl() {
        val built = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .build()

        val dsl = constraints {
            requiredNetworkType = NetworkType.UNMETERED
            requiresCharging = true
        }

        assertEquals(built, dsl)
    }
}
