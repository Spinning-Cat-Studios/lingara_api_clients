package com.getlingara.kotlin.events

import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.TransportKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The feed helper, `client.events(…)`: every event from a cursor up to now, page by page, as a
 * [Flow] (CONTRACT.md, The event helpers; ADR 30.9.26aa D6).
 *
 * Each item is parsed into an [Event]; a type this library does not know is an [UnknownEvent]. The
 * flow completes on the page that says `has_more: false`. It never sleeps and never polls: save
 * [cursor] and collect again later (a second collection continues from it), or move to
 * `tailEvents`. A cursor older than the 30-day window is an `ApiException` `cursor_expired`: start
 * again without one, or with `start = "oldest"`.
 */
public class EventFeed internal constructor(
    cursor: String?,
    private val start: String?,
    private val fetch: suspend (String?, String?) -> JsonObject,
) : Flow<Event> {
    /**
     * Where to continue: after a page's last event is emitted, that page's `next_cursor`. A page with
     * no events still advances it. `null` only before the first page when no cursor was given.
     */
    @Volatile
    public var cursor: String? = cursor
        private set

    /**
     * Collects every event up to now.
     *
     * @throws TransportException `malformed_event` for a known type whose data does not decode
     */
    override suspend fun collect(collector: FlowCollector<Event>) {
        var more = true
        while (more) {
            // start only while there is no cursor: the server's own precedence.
            val page = Page.of(fetch(cursor, start.takeIf { cursor == null }))
            if (page.items.isEmpty()) cursor = page.next
            page.items.forEachIndexed { i, item ->
                collector.emit(parse(item))
                if (i == page.items.lastIndex) cursor = page.next
            }
            more = page.more
        }
    }

    private fun parse(item: JsonElement): Event =
        try {
            Event.parse(item)
        } catch (e: IllegalArgumentException) {
            throw TransportException(TransportKind.MALFORMED_EVENT, e)
        }

    /** One `EventPage`, read from its JSON; a page of another shape is `malformed_response`. */
    private class Page(
        val items: List<JsonElement>,
        val next: String,
        val more: Boolean,
    ) {
        companion object {
            fun of(body: JsonObject): Page {
                val items = body["items"] as? JsonArray
                val next = (body["next_cursor"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val more = (body["has_more"] as? JsonPrimitive)?.booleanOrNull
                if (items == null || next == null || more == null) throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
                return Page(items, next, more)
            }
        }
    }
}
