package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.ryadom.jabbit.JabbitDesktopOptions
import java.net.NetworkInterface
import java.nio.file.Files

internal class DesktopDeviceStateProvider(private val options: JabbitDesktopOptions) :
    DeviceStateProvider {

    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val mutex = Mutex()
    private var snapshot: DeviceState? = null

    override val changes: Flow<DeviceState> = signals.map { current() }

    override suspend fun current(): DeviceState {
        mutex.withLock {
            snapshot?.let { return it }
            return readLocked()
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                refresh()
                delay(options.pollInterval)
            }
        }
    }

    private suspend fun refresh() {
        mutex.withLock { readLocked() }
    }

    private suspend fun readLocked(): DeviceState {
        val fresh = withContext(Dispatchers.IO) { read() }
        val previous = snapshot
        snapshot = fresh

        if (previous != null && previous != fresh) {
            signals.tryEmit(Unit)
        }

        return fresh
    }

    private fun read(): DeviceState {
        val power = if (options.readPowerSource) DesktopPowerSource.read() else PowerStatus()
        val charging = power.charging ?: true
        val level = power.level

        return DeviceState(
            networkConnected = hasUsableInterface(),
            networkMetered = false,
            networkRoaming = false,
            charging = charging,
            batteryNotLow = charging || level == null || level > options.lowBatteryThreshold,
            storageNotLow = usableSpaceBytes() > options.lowStorageThresholdBytes,
            deviceIdle = true
        )
    }

    private fun hasUsableInterface(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.any { it.isUp && !it.isLoopback && it.inetAddresses.hasMoreElements() }
            ?: false
    } catch (_: Throwable) {
        true
    }

    private fun usableSpaceBytes(): Long {
        val existing = generateSequence(options.storageDirectory) { it.parent }
            .firstOrNull { Files.exists(it) }
            ?: return Long.MAX_VALUE

        return try {
            existing.toFile().usableSpace.takeIf { it > 0 } ?: Long.MAX_VALUE
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
    }
}
