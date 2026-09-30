package com.getlingara.kotlin

import kotlin.time.Duration

/**
 * Every failure a call can raise (CONTRACT.md K3): one `catch (e: LingaraException)` handles all
 * four, and a `when (e)` over it is exhaustive. Cancellation is never one of them: a cancelled
 * caller sees its own `CancellationException` (ADR 29.9.26s D8).
 *
 * The subclasses are plain classes, not data classes, so no generated `toString` or `componentN`
 * exposes a field.
 */
public sealed class LingaraException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/**
 * A `/v1` refusal, or a stream's `error` event, whose [status] is then 200. [message] is the
 * envelope's `error` text, or the event's `message`.
 */
public class ApiException(
    /** The HTTP status: 200 for a stream's error event. */
    public val status: Int,
    /** The envelope's code, or `http_<status>` when the body is not the envelope. */
    public val code: String,
    message: String,
    /** The `Retry-After` the library declined to wait for. */
    public val retryAfter: Duration?,
    /** The `plan_id` an error event carried. */
    public val planId: String?,
    /** The `Lingara-Version` the server answered under. */
    public val servedVersion: String?,
) : LingaraException(message, null) {
    override fun toString(): String =
        "ApiException(status=$status, code=$code, message=$message, retryAfter=$retryAfter, " +
            "planId=$planId, servedVersion=$servedVersion)"
}

/** A token-endpoint refusal: RFC 6749 §5.2, or `http_<status>` when the body is not one. */
public class OAuthException(
    /** The HTTP status. */
    public val status: Int,
    /** The RFC 6749 `error`, or `http_<status>`. */
    public val error: String,
    /** The `error_description`. */
    public val description: String?,
    /** The `Retry-After` the library declined to wait for. */
    public val retryAfter: Duration?,
) : LingaraException(
        "token endpoint: $error${description?.let { ": $it" } ?: ""} (HTTP $status)",
        null,
    )

/** Any 503 whose body is not JSON: the service is under maintenance. */
public class MaintenanceException(
    /** The response text, at most 1 KiB, cut on a character boundary. */
    public val body: String,
    /** The `Retry-After`, when the response carried one. */
    public val retryAfter: Duration?,
) : LingaraException("the Lingara API is under maintenance", null)

/**
 * A call with no usable HTTP answer. The [cause], when there is one, is the JDK's own failure; one
 * whose text named a credential is replaced by a scrubbed stand-in, and the [kind] is kept.
 */
public class TransportException(
    /** Why the call had no usable answer. */
    public val kind: TransportKind,
    cause: Throwable?,
) : LingaraException(
        "transport failure: ${kind.wireName}${cause?.message?.let { ": $it" } ?: ""}",
        cause,
    )

/** Why a call had no usable HTTP answer: CONTRACT.md K3's seven kinds. */
public enum class TransportKind {
    /** A connect failure, a connect timeout, or any failure before the response headers. */
    CONNECT,

    /** A TLS failure. */
    TLS,

    /** A failure while reading a body. */
    RESET,

    /** The library's own timers: the stream idle timeout, or a token-exchange attempt. */
    TIMEOUT,

    /** EOF before a stream's terminal event. */
    STREAM_ENDED_EARLY,

    /** A body that does not decode, or a non-SSE `200` on a stream. */
    MALFORMED_RESPONSE,

    /** A known stream event whose data does not decode. */
    MALFORMED_EVENT,
    ;

    /** The kind as the contract spells it, such as `stream_ended_early`. */
    public val wireName: String get() = name.lowercase()
}
