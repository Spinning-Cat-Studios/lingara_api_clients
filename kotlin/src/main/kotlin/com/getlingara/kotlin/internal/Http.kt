package com.getlingara.kotlin.internal

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The library's one scope (ADR 29.9.26s D3), shared by every client in the JVM. It runs what must
 * outlive or stand apart from a caller's coroutine: token flights and each stream's idle watchdog.
 * It is never cancelled and holds no thread while idle, and its timers are real time whatever
 * dispatcher the caller uses.
 */
internal val LibraryScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("lingara-kotlin"))

/**
 * The one JSON configuration every model is read and written with (D2). `ignoreUnknownKeys` is
 * load-bearing: a field the server adds is additive. `explicitNulls = false` decodes an absent
 * nullable field as `null` and leaves a `null` out of a request body.
 */
internal val LingaraJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * Suspends on a `sendAsync` future. Cancellation calls `cancel(true)`, which from JDK 16 aborts the
 * exchange; kotlinx's own `CompletionStage.await()` calls `cancel(false)`, which does not (D3).
 */
internal suspend fun <T> CompletableFuture<T>.awaitCancelling(): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel(true) }
        whenComplete { value, failure ->
            if (failure == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(unwrap(failure))
            }
        }
    }

private fun unwrap(failure: Throwable): Throwable =
    if ((failure is CompletionException || failure is ExecutionException) && failure.cause != null) {
        failure.cause!!
    } else {
        failure
    }

/**
 * One blocking read ([block]) on `Dispatchers.IO`, whose cancellation closes the stream (D3, D8).
 *
 * D3 planned `runInterruptible`, which interrupts the reading thread. JDK 17's body stream
 * (`HttpResponseInputStream.current()`) catches that `InterruptedException` and goes back to
 * waiting, so a cancelled collector stayed blocked until the idle watchdog fired. Closing the
 * stream wakes a blocked read on every release, so cancellation closes it; the read then ends
 * with an `IOException` nobody awaits, and the caller has already resumed with its own
 * `CancellationException`.
 */
internal suspend fun <T> InputStream.cancellableRead(block: InputStream.() -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            try {
                close()
            } catch (e: IOException) {
                // Nothing to do: the read is being abandoned either way.
            }
        }
        CompletableFuture.supplyAsync({ block() }, Dispatchers.IO.asExecutor()).whenComplete { value, failure ->
            if (failure == null) {
                continuation.resume(value)
            } else {
                continuation.resumeWithException(unwrap(failure))
            }
        }
    }

/**
 * A body handler that records when the response headers arrived: a JSON call's future fails with
 * the same `IOException` either way, and the two map to different kinds (D7 steps 4 and 5).
 */
internal class HeadersSeen<T>(
    private val inner: HttpResponse.BodyHandler<T>,
) : HttpResponse.BodyHandler<T> {
    @Volatile
    var arrived: Boolean = false
        private set

    override fun apply(info: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<T> {
        arrived = true
        return inner.apply(info)
    }
}
