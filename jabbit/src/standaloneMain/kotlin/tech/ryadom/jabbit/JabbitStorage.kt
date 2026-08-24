package tech.ryadom.jabbit

/**
 * Persistence used by the platforms where Jabbit owns the job queue itself, currently iOS and the
 * browser.
 *
 * Jobs are stored as a single JSON document. Implement this to move that document somewhere else —
 * the keychain, a file, an app group shared with an extension, or a server-backed store.
 */
public interface JabbitStorage {

    /**
     * Returns the previously stored document, or `null` when nothing has been stored yet.
     */
    public suspend fun read(): String?

    /**
     * Stores [value], replacing whatever was stored before.
     */
    public suspend fun write(value: String)
}
