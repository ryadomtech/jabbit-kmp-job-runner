package tech.ryadom.jabbit.internal

import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.warn
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class SingleInstanceLock(private val file: Path) {

    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    fun tryAcquire(logger: JabbitLogger): Boolean {
        val opened = try {
            file.parent?.let { Files.createDirectories(it) }
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (error: Throwable) {
            logger.warn("Could not open the lock file $file, continuing without it", error)
            return true
        }

        val acquired = try {
            opened.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (error: Throwable) {
            logger.warn("Could not lock $file, continuing without it", error)
            opened.close()
            return true
        }

        if (acquired == null) {
            opened.close()
            return false
        }

        channel = opened
        lock = acquired
        return true
    }

    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }
}
