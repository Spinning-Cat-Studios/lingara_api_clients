package com.getlingara.kotlin.conformance

import com.getlingara.kotlin.ApiException
import com.getlingara.kotlin.ApiResponse
import com.getlingara.kotlin.EventStream
import com.getlingara.kotlin.LingaraClient
import com.getlingara.kotlin.MaintenanceException
import com.getlingara.kotlin.OAuthException
import com.getlingara.kotlin.TransportException
import com.getlingara.kotlin.model.EventPage
import com.getlingara.kotlin.model.InboundEventAccepted
import com.getlingara.kotlin.model.LessonPlan
import com.getlingara.kotlin.model.LessonPlanCreateRequest
import com.getlingara.kotlin.model.TutorTurnRequest
import com.getlingara.kotlin.model.Usage
import com.getlingara.kotlin.model.VersionDetail
import com.getlingara.kotlin.model.VersionList
import com.getlingara.kotlin.model.VocabRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/** One call as the harness saw it, in the contract's vocabulary. */
internal class Seen {
    var outcome: String? = null
    var status: Int? = null
    var body: JsonElement? = null
    val events: MutableList<JsonElement> = mutableListOf()
    var variant: String? = null
    var fields: JsonObject? = null
    var servedVersion: String? = null
    var sleeps: List<Long> = emptyList()
    var hooks: List<JsonElement> = emptyList()

    // An events or tail step's yield: each envelope's id, the UnknownEvent types, the cursor.
    val eventIds: MutableList<String> = mutableListOf()
    val unknownTypes: MutableList<String> = mutableListOf()
    var cursor: String? = null

    // Every rendering of the client and of a raised error.
    val renderings: MutableList<String> = mutableListOf()
}

/** Runs one step's call, n times at once for `parallel: n`, and observes each run. */
internal object Observe {
    suspend fun runStep(
        rig: Rig,
        call: JsonObject,
        expect: JsonObject,
    ): List<String> {
        rig.reset()
        val n = call.number("parallel")?.toInt() ?: 1
        val runs = parallel(n) { invoke(rig.client, call) }
        val operation = call.text("operation")
        return runs.flatMapIndexed { i, seen ->
            seen.sleeps = rig.sleepsSeconds()
            seen.hooks = rig.hookCalls()
            seen.renderings += rig.client.toString()
            val label = if (n > 1) "call ${i + 1}: " else ""
            Compare.compare(expect, seen).map { "$operation: $label$it" }
        }
    }

    /** n coroutines released together by one gate, so all n are in flight before any completes. */
    private suspend fun parallel(
        n: Int,
        run: suspend () -> Seen,
    ): List<Seen> =
        coroutineScope {
            val gate = CompletableDeferred<Unit>()
            val runs =
                (1..n).map {
                    async(Dispatchers.Default) {
                        gate.await()
                        run()
                    }
                }
            gate.complete(Unit)
            runs.awaitAll()
        }

    private suspend fun invoke(
        c: LingaraClient,
        call: JsonObject,
    ): Seen {
        val id = (call["params"] as? JsonObject)?.text("id").orEmpty()
        val cancelAfter = call.number("cancel_after_events")?.toInt() ?: NEVER
        return when (call.text("operation")) {
            "generateVocabulary" -> consume(cancelAfter) { c.generateVocabulary(body(call, VocabRequest.serializer())) }
            "createLessonPlan" -> consume(cancelAfter) { c.createLessonPlan(body(call, LessonPlanCreateRequest.serializer())) }
            "streamLessonPlan" -> consume(cancelAfter) { c.streamLessonPlan(id) }
            "sendTutorMessage" -> consume(cancelAfter) { c.sendTutorMessage(body(call, TutorTurnRequest.serializer())) }
            "streamEvents" -> Query(call["params"] as? JsonObject).let { q -> consume(cancelAfter) { c.streamEvents(q.cursor, q.start, q.types) } }
            else -> invokeJson(c, call, id)
        }
    }

    private suspend fun invokeJson(
        c: LingaraClient,
        call: JsonObject,
        id: String,
    ): Seen {
        val operation = call.text("operation").orEmpty()
        return when (operation) {
            "getLessonPlan" -> result(LessonPlan.serializer()) { c.getLessonPlan(id) }
            "getUsage" -> result(Usage.serializer()) { c.getUsage() }
            "getOpenApiDocument" -> result(JsonObject.serializer()) { c.getOpenApiDocument() }
            "listApiVersions" -> result(VersionList.serializer()) { c.listApiVersions() }
            "getApiVersion" -> result(VersionDetail.serializer()) { c.getApiVersion(id) }
            "getAsyncApiDocument" -> result(JsonObject.serializer()) { c.getAsyncApiDocument() }
            "listEvents" ->
                Query(call["params"] as? JsonObject).let { q -> result(EventPage.serializer()) { c.listEvents(q.cursor, q.start, q.types, q.limit) } }
            // sendEvent's success is a 202, and ApiResponse carries no status.
            "sendEvent" -> result(InboundEventAccepted.serializer()) { EventSteps.send(c, call) }.apply { status = status?.let { 202 } }
            else -> Seen().apply { outcome = "harness: no operation $operation" }
        }
    }

    private fun <T> body(
        call: JsonObject,
        type: DeserializationStrategy<T>,
    ): T = HarnessJson.decodeFromJsonElement(type, call["body"] ?: JsonObject(emptyMap()))

    private suspend fun <T> result(
        type: KSerializer<T>,
        call: suspend () -> ApiResponse<T>,
    ): Seen {
        val response =
            try {
                call()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failed(e, emptyList(), null)
            }
        return Seen().apply {
            outcome = "completed"
            status = 200
            body = HarnessJson.encodeToJsonElement(type, response.body)
            servedVersion = response.servedVersion
        }
    }

    /** Drains a stream; after `cancelAfter` events it cancels the collecting Job. */
    private suspend fun <E : Any> consume(
        cancelAfter: Int,
        send: suspend () -> EventStream<E>,
    ): Seen {
        val stream =
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failed(e, emptyList(), null)
            }
        val seen = Seen()
        seen.servedVersion = stream.servedVersion
        var failure: Exception? = null
        val collector = CoroutineScope(Dispatchers.Default).launch { failure = collectInto(stream, seen, cancelAfter) }
        collector.join()
        failure?.let { return failed(it, seen.events, stream.servedVersion) }
        seen.outcome = if (collector.isCancelled) "cancelled" else "completed"
        seen.status = if (collector.isCancelled) null else 200
        return seen
    }

    /**
     * Collects every event into [seen], cancelling this Job after the [cancelAfter]-th; returns
     * what the stream threw, or `null`.
     */
    private suspend fun <E : Any> CoroutineScope.collectInto(
        stream: EventStream<E>,
        seen: Seen,
        cancelAfter: Int,
    ): Exception? =
        try {
            stream.use { events ->
                events.collect {
                    seen.events += event(it)
                    if (seen.events.size == cancelAfter) cancel()
                }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }

    /** A union member as `{event, data}`: its class name, snake-cased, and its payload. */
    private fun event(event: Any): JsonElement {
        val member = HarnessJson.encodeToJsonElement(serializer(event.javaClass), event).jsonObject
        val name =
            event.javaClass.simpleName
                .replace(CAMEL_HUMP) { "${it.value[0]}_${it.value[1]}" }
                .lowercase()
        return buildJsonObject {
            put("event", name)
            put("data", member.getValue("data"))
        }
    }

    private fun failed(
        e: Exception,
        events: List<JsonElement>,
        served: String?,
    ): Seen =
        Seen().apply {
            outcome = "error"
            this.events += events
            servedVersion = served
            variantOf(e, this)
            generateSequence<Throwable>(e) { it.cause }.forEach {
                renderings += it.toString()
                renderings += it.message.toString()
            }
        }

    /** The contract's variant name and snake_case fields for a raised exception. */
    fun variantOf(
        e: Exception,
        seen: Seen,
    ) {
        val variant =
            when (e) {
                is ApiException -> Pair("ApiError", apiFields(e))
                is OAuthException -> Pair("OAuthError", oauthFields(e))
                is MaintenanceException -> Pair("MaintenanceError", maintenanceFields(e))
                is TransportException -> Pair("TransportError", buildJsonObject { put("kind", e.kind.wireName) })
                else -> Pair("not a known variant", buildJsonObject { put("debug", e.toString()) })
            }
        seen.variant = variant.first
        seen.fields = variant.second
    }

    private fun apiFields(e: ApiException): JsonObject =
        buildJsonObject {
            put("status", e.status)
            put("code", e.code)
            put("message", e.message)
            put("retry_after", e.retryAfter.seconds())
            put("plan_id", e.planId)
            put("served_version", e.servedVersion)
        }

    private fun maintenanceFields(e: MaintenanceException): JsonObject =
        buildJsonObject {
            put("body", e.body)
            put("retry_after", e.retryAfter.seconds())
        }

    private fun oauthFields(e: OAuthException): JsonObject =
        buildJsonObject {
            put("status", e.status)
            put("error", e.error)
            put("description", e.description)
            put("retry_after", e.retryAfter.seconds())
        }

    private fun Duration?.seconds(): Long? = this?.inWholeSeconds

    /** A `cancel_after_events` no event count reaches. */
    private const val NEVER = 0

    /** A lower-case letter or digit followed by a capital: where snake_case puts an underscore. */
    private val CAMEL_HUMP = Regex("[a-z0-9][A-Z]")
}
