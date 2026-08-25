package tech.ryadom.jabbit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * [JabbitStorage] backed by a file on disk. This is the default on the desktop.
 *
 * Writes go to a temporary file next to [file] and are moved into place atomically, so a crash
 * halfway through a write leaves the previous queue intact rather than a truncated document.
 */
public class FileJabbitStorage(private val file: Path) : JabbitStorage {

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        if (Files.exists(file)) Files.readString(file) else null
    }

    override suspend fun write(value: String) {
        withContext(Dispatchers.IO) {
            file.parent?.let { Files.createDirectories(it) }

            val temporary = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temporary, value)

            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
