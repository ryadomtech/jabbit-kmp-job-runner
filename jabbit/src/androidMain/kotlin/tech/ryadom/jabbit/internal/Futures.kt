package tech.ryadom.jabbit.internal

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val DirectExecutor = Executor { runnable -> runnable.run() }

internal suspend fun <T> ListenableFuture<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    // future is guaranteed to be done here
                    @Suppress("BlockingMethodInNonBlockingContext")
                    continuation.resume(get())
                } catch (error: ExecutionException) {
                    continuation.resumeWithException(error.cause ?: error)
                } catch (error: CancellationException) {
                    continuation.cancel(error)
                }
            },
            DirectExecutor
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
