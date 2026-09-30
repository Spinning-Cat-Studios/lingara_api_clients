package com.getlingara.kotlin.internal

import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.LingaraException
import com.getlingara.kotlin.MaintenanceException
import com.getlingara.kotlin.OAuthException
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpHeaders
import java.nio.channels.UnresolvedAddressException
import java.time.Instant
import javax.net.ssl.SSLException

/** Which endpoint refused: the two map their bodies differently. */
internal enum class Endpoint { V1, TOKEN }

/** Maps a refused response, or a failed request, to its K3 exception (ADR 29.9.26s D7). */
internal object ErrorMapper {
    private const val MAINTENANCE_BODY_BYTES = 1024

    /**
     * Maps a non-2xx response in the contract's precedence: a non-JSON 503 from either endpoint is
     * maintenance first, then the endpoint's own body shape.
     */
    fun refusal(
        endpoint: Endpoint,
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        now: Instant,
    ): LingaraException {
        val retryAfter = Retry.retryAfter(headers, now)
        if (status == 503 && !isJson(headers)) {
            return MaintenanceException(truncate(body, MAINTENANCE_BODY_BYTES), retryAfter)
        }
        val json = parse(body)
        if (endpoint == Endpoint.TOKEN) {
            val error = text(json, "error") ?: return OAuthException(status, "http_$status", null, retryAfter)
            return OAuthException(status, error, text(json, "error_description"), retryAfter)
        }
        val served = headers.firstValue("Lingara-Version").orElse(null)
        val code = text(json, "code")
        val message = text(json, "error")
        if (code == null || message == null) {
            return ApiException(status, "http_$status", "HTTP $status", retryAfter, null, served)
        }
        return ApiException(status, code, message, retryAfter, null, served)
    }

    /**
     * Maps a failed send ([afterHeaders] false) or body read (true), in D7's order, and scrubs any
     * credential from the cause.
     */
    fun transport(
        failure: Throwable,
        afterHeaders: Boolean,
        secrets: Collection<String>,
    ): TransportException = TransportException(kind(failure, afterHeaders), scrub(failure, secrets))

    fun kind(
        failure: Throwable,
        afterHeaders: Boolean,
    ): TransportKind =
        when {
            inChain(failure) { it is SSLException } -> TransportKind.TLS
            inChain(failure) { it.isConnectFailure() } -> TransportKind.CONNECT
            afterHeaders -> TransportKind.RESET
            else -> TransportKind.CONNECT
        }

    private fun Throwable.isConnectFailure(): Boolean =
        this is HttpConnectTimeoutException || this is ConnectException || this is UnresolvedAddressException

    private fun inChain(
        failure: Throwable,
        test: (Throwable) -> Boolean,
    ): Boolean = generateSequence(failure) { it.cause }.any(test)

    /**
     * Returns the failure, or a stand-in when any message in its chain names a credential. The
     * JDK's HTTP client does not echo request headers or bodies, so this is defence in depth.
     */
    fun scrub(
        failure: Throwable,
        secrets: Collection<String>,
    ): Throwable {
        val leaks =
            generateSequence(failure) { it.cause }.any { t ->
                val message = t.message.toString()
                secrets.any { it.isNotEmpty() && message.contains(it) }
            }
        return if (leaks) IOException("the underlying error was withheld: it contained a credential") else failure
    }

    /** The `Content-Type`'s media type, lower-cased, with its parameters dropped. */
    fun mediaType(headers: HttpHeaders): String =
        headers
            .firstValue("Content-Type")
            .orElse("")
            .substringBefore(';')
            .trim()
            .lowercase()

    private fun isJson(headers: HttpHeaders): Boolean {
        val media = mediaType(headers)
        return media == "application/json" || media.endsWith("+json")
    }

    /** At most [max] bytes of UTF-8, cut on a character boundary. */
    fun truncate(
        body: ByteArray,
        max: Int,
    ): String {
        var end = body.size
        if (end > max) {
            end = max
            while (end > 0 && (body[end].toInt() and 0xC0) == 0x80) end--
        }
        return String(body, 0, end, Charsets.UTF_8)
    }

    private fun parse(body: ByteArray): JsonElement? =
        try {
            LingaraJson.parseToJsonElement(String(body, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            null
        }

    /** A string field of a JSON object, or `null`. */
    fun text(
        json: JsonElement?,
        field: String,
    ): String? {
        val value = (json as? JsonObject)?.get(field) as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }
}
