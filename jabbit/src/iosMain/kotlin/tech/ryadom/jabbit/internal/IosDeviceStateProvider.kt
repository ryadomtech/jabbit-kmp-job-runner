package tech.ryadom.jabbit.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.darwin.dispatch_get_main_queue
import tech.ryadom.jabbit.JabbitIosOptions

internal class IosDeviceStateProvider(
    private val options: JabbitIosOptions
) : DeviceStateProvider {

    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var networkConnected: Boolean = true
    private var networkExpensive: Boolean = false
    private var backgroundProcessing: Boolean = false

    override val changes: Flow<DeviceState> = signals.map { current() }

    fun start() {
        startNetworkMonitor()
        startBatteryMonitor()
    }

    fun setBackgroundProcessing(active: Boolean) {
        backgroundProcessing = active
        signals.tryEmit(Unit)
    }

    override suspend fun current(): DeviceState = withContext(Dispatchers.Main) {
        val device = UIDevice.currentDevice
        val batteryState = device.batteryState
        val charging = batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        val level = device.batteryLevel

        DeviceState(
            networkConnected = networkConnected,
            networkMetered = networkExpensive,
            networkRoaming = false,
            charging = charging,
            batteryNotLow = charging || level < 0f || level > options.lowBatteryThreshold,
            storageNotLow = freeDiskBytes() > options.lowStorageThresholdBytes,
            deviceIdle = backgroundProcessing
        )
    }

    private fun startNetworkMonitor() {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            networkConnected = nw_path_get_status(path) == nw_path_status_satisfied
            networkExpensive = nw_path_is_expensive(path) || nw_path_is_constrained(path)
            signals.tryEmit(Unit)
        }
        nw_path_monitor_start(monitor)
    }

    private fun startBatteryMonitor() {
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val center = NSNotificationCenter.defaultCenter
        listOf(
            UIDeviceBatteryStateDidChangeNotification,
            UIDeviceBatteryLevelDidChangeNotification
        ).forEach { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = NSOperationQueue.mainQueue
            ) { signals.tryEmit(Unit) }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun freeDiskBytes(): Long {
        val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(
            path = NSHomeDirectory(),
            error = null
        ) ?: return Long.MAX_VALUE
        val free = attributes[NSFileSystemFreeSize] as? NSNumber ?: return Long.MAX_VALUE
        return free.longLongValue
    }
}
