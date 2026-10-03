package com.getlingara.kotlin.internal

import com.getlingara.kotlin.AccessToken
import com.getlingara.kotlin.ClientSecret
import com.getlingara.kotlin.TokenSource
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** One `/v1` request, before auth. */
internal class Call(
    val method: String,
    val path: String,
    val body: ByteArray?,
    val accept: String,
    val needsToken: Boolean,
) {
    /** The request's own headers: `Idempotency-Key`, `Last-Event-ID` (ADR 30.9.26aa). */
    var headers: Map<String, String> = emptyMap()

    /** Sent with no K4 loop, as a tail open is (CONTRACT.md K5a); K1's refresh still applies. */
    var once: Boolean = false
}

/** What a pipeline shares with its client: the base URL, the pin and the two headers' inputs. */
internal class Target(
    val baseUrl: String,
    val version: String?,
    val userAgent: String,
    val secret: ClientSecret?,
)

/**
 * The request pipeline behind every `/v1` call (ADR 29.9.26s D4, D5): auth and K1's one 401 retry,
 * K4's loop, and the refusal mapping. It returns only a 2xx.
 */
internal class Pipeline(
    private val target: Target,
    private val policy: Policy,
    private val tokens: TokenSource?,
    private val http: HttpClient,
) {
    /** Sends [call], reading its body through [handler], and throws on any non-2xx. */
    suspend fun <T> send(
        call: Call,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val response = authorised(call, handler)
        val status = response.statusCode()
        if (status in 200..299) return response
        val body = refusalBody(response.body())
        throw ErrorMapper.refusal(Endpoint.V1, status, response.headers(), body, policy.clock.instant())
    }

    /**
     * K1's one 401 retry: on a 401, forget that token (only if it is still cached), get another and
     * send once more, with a fresh K4 budget. A client with no token source sends an operation that
     * needs one without `Authorization`, and the server's 401 is the answer.
     */
    private suspend fun <T> authorised(
        call: Call,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        if (tokens == null || !call.needsToken) return attempts(call, null, handler)
        val first = tokens.token()
        val response = attempts(call, first, handler)
        if (response.statusCode() != 401) return response
        Retry.discard(response)
        tokens.invalidate(first)
        return attempts(call, tokens.token(), handler)
    }

    private suspend fun <T> attempts(
        call: Call,
        token: AccessToken?,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> =
        if (call.once) {
            sendOnce(call, token, handler)
        } else {
            Retry.withRetries(policy) { sendOnce(call, token, handler) }
        }

    private suspend fun <T> sendOnce(
        call: Call,
        token: AccessToken?,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val seen = HeadersSeen(handler)
        return try {
            http.sendAsync(request(call, token), seen).awaitCancelling()
        } catch (e: IOException) {
            throw ErrorMapper.transport(e, seen.arrived, secrets(token))
        }
    }

    private fun request(
        call: Call,
        token: AccessToken?,
    ): HttpRequest {
        val publisher = call.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody()
        val builder =
            HttpRequest
                .newBuilder(URI.create(target.baseUrl + call.path))
                .method(call.method, publisher)
                .header("Accept", call.accept)
                .header("User-Agent", target.userAgent)
        if (call.body != null) builder.header("Content-Type", "application/json")
        if (token != null) builder.header("Authorization", "Bearer " + token.exposeSecret())
        target.version?.let { builder.header("Lingara-Version", it) }
        call.headers.forEach(builder::header)
        return builder.build()
    }

    /** At most 64 KiB of a refusal's body; an unreadable one is empty. */
    private suspend fun refusalBody(body: Any?): ByteArray =
        when (body) {
            is ByteArray -> body
            is InputStream ->
                try {
                    body.use { it.cancellableRead { readNBytes(REFUSAL_BODY_BYTES) } }
                } catch (e: IOException) {
                    ByteArray(0)
                }
            else -> ByteArray(0)
        }

    /** The values no cause may carry. */
    fun secrets(token: AccessToken?): List<String> = listOfNotNull(token?.exposeSecret(), target.secret?.exposeSecret())

    private companion object {
        const val REFUSAL_BODY_BYTES = 64 * 1024
    }
}
