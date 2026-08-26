package tech.ryadom.jabbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class JabbitDslTest {

    @Test
    fun collectsEveryPlatformBlockIndependently() {
        val queueStorage = InMemoryJabbitStorage()

        val scope = JabbitScope().apply {
            worker(OkJob) { JabbitWorker { JobResult.success(1) } }

            android { }

            ios {
                backgroundTaskIdentifier = "com.example.jabbit"
                lowBatteryThreshold = 0.3f
            }

            desktop {
                applicationName = "Example"
                storageDirectory = "/tmp/example"
                singleInstanceLock = false
                pollInterval = 5.minutes
            }

            browser {
                queueName = "example"
                storage = queueStorage
                backgroundSyncTag = null
                periodicSyncInterval = 3.hours
            }
        }

        assertEquals("com.example.jabbit", scope.iosOptions.backgroundTaskIdentifier)
        assertEquals(0.3f, scope.iosOptions.lowBatteryThreshold)

        assertEquals("Example", scope.desktopOptions.applicationName)
        assertEquals("/tmp/example", scope.desktopOptions.storageDirectory)
        assertEquals(false, scope.desktopOptions.singleInstanceLock)
        assertEquals(5.minutes, scope.desktopOptions.pollInterval)

        assertEquals("example", scope.browserOptions.queueName)
        assertEquals(queueStorage, scope.browserOptions.storage)
        assertNull(scope.browserOptions.backgroundSyncTag)
        assertEquals(3.hours, scope.browserOptions.periodicSyncInterval)
    }

    @Test
    fun keepsThePlatformDefaultsUntilTheyAreOverridden() {
        val scope = JabbitScope().apply { worker(OkJob) { JabbitWorker { JobResult.success(1) } } }

        assertNull(scope.iosOptions.backgroundTaskIdentifier)
        assertNull(scope.iosOptions.storage)
        assertNull(scope.desktopOptions.applicationName)
        assertTrue(scope.desktopOptions.singleInstanceLock)
        assertTrue(scope.desktopOptions.readPowerSource)
        assertEquals("jabbit", scope.browserOptions.queueName)
        assertEquals("tech.ryadom.jabbit.sync", scope.browserOptions.backgroundSyncTag)
        assertTrue(scope.browserOptions.coordinateTabs)
        assertEquals(false, scope.browserOptions.requestPersistentStorage)
    }

    @Test
    fun refusesToBuildWithoutWorkers() {
        assertFailsWith<IllegalStateException> { JabbitScope().buildConfiguration() }
    }
}
