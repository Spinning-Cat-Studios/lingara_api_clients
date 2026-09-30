package com.getlingara.kotlin.internal

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpHeaders
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * K4's knobs and the two seams they are read and slept against (C2 D5, D9): tries per request,
 * the first included; the longest `Retry-After` worth waiting for; what an HTTP-date is read
 * against; and what waits it out.
 */
internal class Policy(
    val maxAttempts: Int,
    val cap: Duration,
    val clock: Clock,
    val sleeper: suspend (Duration) -> Unit,
)

/**
 * K4's `Retry-After` loop around one HTTP request (CONTRACT.md K4). The one 401 retry around a
 * `/v1` call is the pipeline's.
 *
 * Each HTTP request has its own budget of `maxAttempts`. The decision is made on the status line
 * and headers alone, before any byte of the body reaches the caller. A transport failure is never
 * retried: it is raised by the attempt itself. The loop runs in the calling coroutine and waits
 * through the sleeper, whose default `delay` is cancellable.
 */
internal object Retry {
    /** Clamps a huge delta-seconds, which is above any cap either way. */
    private const val MAX_SECONDS = 1L shl 32

    suspend fun <T> withRetries(
        policy: Policy,
        attempt: suspend () -> HttpResponse<T>,
    ): HttpResponse<T> {
        var tries = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = attempt()
            val wait = retryWait(policy, response, tries) ?: return response
            discard(response)
            policy.sleeper(wait)
            tries++
        }
    }

    private fun retryWait(
        policy: Policy,
        response: HttpResponse<*>,
        tries: Int,
    ): Duration? {
        val status = response.statusCode()
        if ((status != 429 && status != 503) || tries >= policy.maxAttempts) return null
        return retryAfter(response.headers(), policy.clock.instant())?.takeIf { it <= policy.cap }
    }

    /**
     * Reads `Retry-After` as delta-seconds, or as an HTTP-date against [now]: `max(0, date − now)`,
     * rounded up to a whole second. Absent or unreadable is `null`.
     */
    fun retryAfter(
        headers: HttpHeaders,
        now: Instant,
    ): Duration? {
        val value = headers.firstValue("Retry-After").orElse("").trim()
        if (value.isEmpty()) return null
        if (value.all { it in '0'..'9' }) {
            val digits = if (value.length > 12) MAX_SECONDS else value.toLong()
            return minOf(digits, MAX_SECONDS).seconds
        }
        return try {
            val at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val millis =
                maxOf(
                    0,
                    java.time.Duration
                        .between(now, at)
                        .toMillis(),
                )
            ((millis + 999) / 1000).seconds
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Closes a streamed response's body unread; a buffered one needs nothing. */
    fun discard(response: HttpResponse<*>) {
        val body = response.body()
        if (body is InputStream) {
            try {
                body.close()
            } catch (e: IOException) {
                // Nothing to do: the response is being dropped.
            }
        }
    }
}
