package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.events.Event
import com.getlingara.kotlin.events.InboundEvent
import com.getlingara.kotlin.events.UnknownEvent
import com.getlingara.kotlin.events.events
import com.getlingara.kotlin.events.tailEvents
import com.getlingara.kotlin.model.InboundEventAccepted
import com.getlingara.kotlin.model.WorldContextChanged
import com.getlingara.kotlin.model.WorldPracticeRequested
import kotlinx.coroutines.flow.take
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

/** An events call's query, from a step or a call's `params`. */
internal class Query(
    params: JsonObject?,
) {
    val cursor: String? = params?.text("cursor")
    val start: String? = params?.text("start")
    val types: List<String> = (params?.get("types") as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
    val limit: Int? = params?.number("limit")?.toInt()
}

/**
 * The event helpers' steps (ADR 30.9.26aa D9): `events` collects `client.events(…)` to its end,
 * `tail` takes `take` events from `client.tailEvents(…)` and then stops it, and `sendEvent` builds
 * its [InboundEvent] from the case's `{type, data}` through the public API.
 */
internal object EventSteps {
    /** One `events` or `tail` step, observed and compared. */
    suspend fun runStep(
        rig: Rig,
        kind: String,
        step: JsonObject,
        expect: JsonObject,
    ): List<String> {
        rig.reset()
        val seen = Seen()
        try {
            if (kind == "events") events(rig.client, step, seen) else tail(rig.client, step, seen)
            seen.outcome = "completed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Observe.variantOf(e, seen)
            seen.outcome = "error"
        }
        seen.sleeps = rig.sleepsSeconds()
        seen.hooks = rig.hookCalls()
        return Compare.compare(expect, seen).map { "$kind: $it" }
    }

    /** `sendEvent` with the case's body as an [InboundEvent], and its key if any. */
    suspend fun send(
        c: LingaraClient,
        call: JsonObject,
    ): ApiResponse<InboundEventAccepted> {
        val body = call["body"] as? JsonObject ?: JsonObject(emptyMap())
        val data = body["data"] ?: JsonObject(emptyMap())
        val type = body.text("type")
        val event =
            when (type) {
                "world.context_changed" ->
                    InboundEvent.WorldContextChanged(HarnessJson.decodeFromJsonElement(WorldContextChanged.serializer(), data))
                "world.practice_requested" ->
                    InboundEvent.WorldPracticeRequested(HarnessJson.decodeFromJsonElement(WorldPracticeRequested.serializer(), data))
                else -> throw IllegalArgumentException("no inbound type $type")
            }
        return c.sendEvent(event, call.text("idempotency_key"))
    }

    private suspend fun events(
        c: LingaraClient,
        step: JsonObject,
        seen: Seen,
    ) {
        val query = Query(step)
        val feed = c.events(query.cursor, query.start, query.types)
        try {
            feed.collect { record(seen, it) }
        } finally {
            seen.cursor = feed.cursor
        }
    }

    /** Takes `take` events, then the flow stops the tail: the outcome is then `completed`. */
    private suspend fun tail(
        c: LingaraClient,
        step: JsonObject,
        seen: Seen,
    ) {
        val query = Query(step)
        val tail = c.tailEvents(query.cursor, query.start, query.types)
        try {
            tail.take(step.number("take")?.toInt() ?: 0).collect { record(seen, it) }
        } finally {
            seen.cursor = tail.cursor
        }
    }

    private fun record(
        seen: Seen,
        event: Event,
    ) {
        seen.eventIds += event.id
        if (event is UnknownEvent) seen.unknownTypes += event.type
    }
}
