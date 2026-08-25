package tech.ryadom.jabbit

import kotlinx.coroutines.suspendCancellableCoroutine
import tech.ryadom.jabbit.internal.idbRead
import tech.ryadom.jabbit.internal.idbWrite
import tech.ryadom.jabbit.internal.localStorageRead
import tech.ryadom.jabbit.internal.localStorageWrite
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DEFAULT_DATABASE = "jabbit"
private const val DEFAULT_STORE = "jobs"
private const val DEFAULT_KEY = "queue"

/**
 * [JabbitStorage] backed by IndexedDB. This is the default on the web.
 *
 * IndexedDB is the only persistence a service worker can reach, so this is the storage to keep if
 * jobs should survive the page being closed.
 */
public class IndexedDbJabbitStorage(
    private val databaseName: String = DEFAULT_DATABASE,
    private val storeName: String = DEFAULT_STORE,
    private val key: String = DEFAULT_KEY
) : JabbitStorage {

    override suspend fun read(): String? = suspendCancellableCoroutine { continuation ->
        idbRead(
            databaseName = databaseName,
            storeName = storeName,
            key = key,
            onValue = { value -> if (continuation.isActive) continuation.resume(value) },
            onError = { message ->
                if (continuation.isActive) {
                    continuation.resumeWithException(JabbitStorageException(message))
                }
            }
        )
    }

    override suspend fun write(value: String): Unit = suspendCancellableCoroutine { continuation ->
        idbWrite(
            databaseName = databaseName,
            storeName = storeName,
            key = key,
            value = value,
            onDone = { if (continuation.isActive) continuation.resume(Unit) },
            onError = { message ->
                if (continuation.isActive) {
                    continuation.resumeWithException(JabbitStorageException(message))
                }
            }
        )
    }
}

/**
 * [JabbitStorage] backed by `localStorage`.
 *
 * Simpler and synchronous, but capped at a few megabytes and **not reachable from a service
 * worker**: jobs stored here only run while a page of the app is open.
 */
public class LocalStorageJabbitStorage(private val key: String = "tech.ryadom.jabbit.jobs") :
    JabbitStorage {

    override suspend fun read(): String? = localStorageRead(key)

    override suspend fun write(value: String) {
        localStorageWrite(key, value)
    }
}

/**
 * Raised when the browser refuses to read or write the job queue.
 */
public class JabbitStorageException(message: String) : RuntimeException(message)
