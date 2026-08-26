package tech.ryadom.jabbit

import kotlinx.coroutines.suspendCancellableCoroutine
import tech.ryadom.jabbit.internal.requestStoragePersistence
import kotlin.coroutines.resume

/**
 * Asks the browser to keep this origin's storage, and reports whether it now will.
 *
 * Without it the queue lives in best-effort storage, which a browser is free to evict when the
 * device runs low on space — pending jobs would disappear without a trace. With it the queue
 * survives until the user clears the site's data.
 *
 * Call it at a moment that makes sense for the app rather than on every start: Firefox asks the
 * user for permission, while Chromium and Safari decide silently from how engaged the user is. The
 * request is skipped when the browser already granted persistence, so calling it twice costs
 * nothing.
 *
 * Returns `false` where the Storage API is missing, which is the same as saying "the queue may be
 * evicted", never that something went wrong.
 */
public suspend fun requestPersistentStorage(): Boolean =
    suspendCancellableCoroutine { continuation ->
        requestStoragePersistence { granted ->
            if (continuation.isActive) continuation.resume(granted)
        }
    }
