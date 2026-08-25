package tech.ryadom.jabbit.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import tech.ryadom.jabbit.DesktopJabbit
import tech.ryadom.jabbit.Jabbit

internal class DesktopJabbitImpl(
    delegate: Jabbit,
    private val scope: CoroutineScope,
    private val lock: SingleInstanceLock?
) : DesktopJabbit,
    Jabbit by delegate {

    override fun close() {
        scope.cancel()
        lock?.release()
    }
}
