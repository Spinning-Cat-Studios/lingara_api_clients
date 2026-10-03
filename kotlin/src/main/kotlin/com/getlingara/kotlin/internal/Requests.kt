package com.getlingara.kotlin.internal

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.EventStream
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import kotlinx.serialization.KSerializer
import java.net.http.HttpResponse
import kotlin.time.Duration

/**
 * A client's two response shapes over its pipeline: a JSON body, and an open event stream. Kept
 * apart from `LingaraClient` so the client's file holds its operations (ADR 30.9.26aa).
 */
internal class Requests(
    private val pipeline: Pipeline,
    private val deprecations: Deprecations,
    private val streamIdleTimeout: Duration,
) {
    /** Sends [call] and decodes its body; one that does not decode is `malformed_response`. */
    suspend fun <T> json(
        call: Call,
        serializer: KSerializer<T>,
    ): ApiResponse<T> {
        val response = pipeline.send(call, HttpResponse.BodyHandlers.ofByteArray())
        val served = deprecations.observe(response.headers(), response.request().uri())
        val body =
            try {
                LingaraJson.decodeFromString(serializer, String(response.body(), Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                throw TransportException(TransportKind.MALFORMED_RESPONSE, e)
            }
        return ApiResponse(body, served)
    }

    /** A `GET` of [path] decoded through [serializer]. */
    suspend fun <T> get(
        path: String,
        needsToken: Boolean,
        serializer: KSerializer<T>,
    ): ApiResponse<T> = json(Call("GET", path, null, "application/json", needsToken), serializer)

    /** Opens [route]'s stream with its JSON [body] and path [id], either of which may be absent. */
    suspend fun <E : Any> stream(
        route: Streams.Route<E>,
        body: String?,
        id: String?,
    ): EventStream<E> {
        val path = route.path.replace("{id}", id?.let(::encodeSegment) ?: "")
        return open(route, Call(route.method, path, body?.toByteArray(), "text/event-stream", true))
    }

    /** Sends [call] and returns its event stream; a 200 that is not SSE is `malformed_response`. */
    suspend fun <E : Any> open(
        route: Streams.Route<E>,
        call: Call,
    ): EventStream<E> {
        val response = pipeline.send(call, HttpResponse.BodyHandlers.ofInputStream())
        if (ErrorMapper.mediaType(response.headers()) != "text/event-stream") {
            Retry.discard(response)
            throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
        }
        val served = deprecations.observe(response.headers(), response.request().uri())
        return EventStream(route, response.body(), streamIdleTimeout, served)
    }
}

/** Percent-encodes everything but `A–Z a–z 0–9 - . _ ~`, as the other libraries do. */
internal fun encodeSegment(segment: String): String =
    segment.toByteArray().joinToString("") { byte ->
        val c = byte.toInt().toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") c.toString() else "%%%02X".format(byte.toInt() and 0xFF)
    }

/** An events query: each value encoded, and `types` one comma-separated value (ADR 30.9.26aa). */
internal fun eventsQuery(
    cursor: String?,
    start: String?,
    types: List<String>,
    limit: Int?,
): String {
    val pairs =
        listOfNotNull(
            cursor?.let { "cursor=" + encodeSegment(it) },
            start?.let { "start=" + encodeSegment(it) },
            types.takeIf { it.isNotEmpty() }?.let { "types=" + it.joinToString(",", transform = ::encodeSegment) },
            limit?.let { "limit=$it" },
        )
    return if (pairs.isEmpty()) "" else pairs.joinToString("&", prefix = "?")
}
