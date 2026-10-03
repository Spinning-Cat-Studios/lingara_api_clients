package com.getlingara.kotlin.events

import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.internal.eventsQuery
import kotlinx.serialization.json.JsonObject

/**
 * Reads every event from [cursor] up to now, page by page, each parsed into an [Event] (scope
 * `events:read`; ADR 30.9.26aa D6): from [start] (`latest` or `oldest`) when there is no cursor,
 * only [types] when given. It sends nothing until collected, and never sleeps or polls; see
 * [EventFeed].
 *
 * The two event helpers are extensions rather than members, so `LingaraClient` keeps one member
 * per operation; import them from `com.getlingara.kotlin.events`.
 */
public fun LingaraClient.events(
    cursor: String? = null,
    start: String? = null,
    types: List<String> = emptyList(),
): EventFeed =
    EventFeed(cursor, start) { pageCursor, pageStart ->
        requests.get("/v1/events" + eventsQuery(pageCursor, pageStart, types, null), true, JsonObject.serializer()).body
    }

/**
 * Tails the event stream, reconnecting after every ending from the last `id:` seen (CONTRACT.md
 * K5a; scope `events:read`): [cursor] is the first open's `Last-Event-ID`, and without one [start]
 * goes in the query. Every reopen repeats the first URL. It sends nothing until collected; see
 * [EventTail]. Its [EventTail.cursor] hands over to and from [events].
 */
public fun LingaraClient.tailEvents(
    cursor: String? = null,
    start: String? = null,
    types: List<String> = emptyList(),
): EventTail {
    val path = Streams.STREAM_EVENTS.path + eventsQuery(null, start.takeIf { cursor == null }, types, null)
    return EventTail(cursor, policy, tailMaxFailures) { lastEventId ->
        val call = Call("GET", path, null, "text/event-stream", true)
        call.once = true
        lastEventId?.let { call.headers = mapOf("Last-Event-ID" to it) }
        requests.open(TAIL_ROUTE, call)
    }
}

/** `streamEvents`' route with its `event` frames parsed into the [Event] union (D3). */
private val TAIL_ROUTE: Streams.Route<Event> =
    Streams.STREAM_EVENTS.let { route ->
        Streams.Route(
            route.operationId,
            route.method,
            route.path,
            route.requestBody,
            route.pathParameters,
            route.events,
            route.endsOn,
            route.dataSerializers,
        ) { name, data, _ -> if (name == "event") Event.parse(data) else null }
    }
