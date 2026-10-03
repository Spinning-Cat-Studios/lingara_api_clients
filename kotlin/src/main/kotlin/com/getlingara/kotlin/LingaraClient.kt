package com.getlingara.kotlin

import com.getlingara.kotlin.events.InboundEvent
import com.getlingara.kotlin.events.toJson
import com.getlingara.kotlin.internal.BuildInfo
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.Deprecations
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Pipeline
import com.getlingara.kotlin.internal.Requests
import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.internal.Target
import com.getlingara.kotlin.internal.UserAgent
import com.getlingara.kotlin.internal.encodeSegment
import com.getlingara.kotlin.internal.eventsQuery
import com.getlingara.kotlin.model.CreateLessonPlanEvent
import com.getlingara.kotlin.model.EventPage
import com.getlingara.kotlin.model.GenerateVocabularyEvent
import com.getlingara.kotlin.model.InboundEventAccepted
import com.getlingara.kotlin.model.LessonPlan
import com.getlingara.kotlin.model.LessonPlanCreateRequest
import com.getlingara.kotlin.model.SendTutorMessageEvent
import com.getlingara.kotlin.model.StreamEventsEvent
import com.getlingara.kotlin.model.StreamLessonPlanEvent
import com.getlingara.kotlin.model.TutorTurnRequest
import com.getlingara.kotlin.model.Usage
import com.getlingara.kotlin.model.VersionDetail
import com.getlingara.kotlin.model.VersionList
import com.getlingara.kotlin.model.VocabRequest
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpClient
import java.util.UUID

/**
 * Builds a client (ADR 29.9.26s D4). With no `clientCredentials` the client can call the four
 * operations that need no token.
 *
 * @throws IllegalArgumentException for an empty `version`, for `tokenSource` beside
 *   `clientCredentials`, and for `maxAttempts` below 1
 */
public fun LingaraClient(configure: LingaraClientBuilder.() -> Unit = {}): LingaraClient = LingaraClientBuilder().apply(configure).build()

/**
 * The Lingara API client (ADR 29.9.26s D4). It is safe for concurrent use and needs no `close()`.
 *
 * Every method is a `suspend fun` whose cancellation is the caller's own: cancelling aborts the
 * exchange and throws the caller's `CancellationException`, never a [LingaraException]. A stream
 * method sends its request at once and returns an [EventStream] when the response headers are in,
 * so a refusal is thrown by the call and an in-stream failure into the collector.
 */
public class LingaraClient internal constructor(
    options: LingaraClientBuilder,
    private val tokens: TokenSource?,
    http: HttpClient,
) {
    private val baseUrl = options.baseUrl.toString().trimEnd('/')
    private val clientId = options.clientId
    private val secret = options.clientSecret
    private val version = options.version
    private val pipeline =
        Pipeline(Target(baseUrl, version, UserAgent.of(options.userAgentSuffix), secret), options.policy(), tokens, http)

    /** The JSON and stream requests every operation and event helper sends. */
    internal val requests = Requests(pipeline, Deprecations(options.onDeprecation), options.streamIdleTimeout)

    /** K4's knobs, which the tail's backoff reads too (CONTRACT.md K5a). */
    internal val policy = options.policy()

    /** The consecutive failures after which a tail raises the last. */
    internal val tailMaxFailures: Int = options.tailMaxFailures

    /** Streams a vocabulary list (scope `vocab:generate`). */
    public suspend fun generateVocabulary(body: VocabRequest): EventStream<GenerateVocabularyEvent> =
        requests.stream(Streams.GENERATE_VOCABULARY, LingaraJson.encodeToString(VocabRequest.serializer(), body), null)

    /** Streams a new lesson plan's generation (scope `lesson_plans:write`). */
    public suspend fun createLessonPlan(body: LessonPlanCreateRequest): EventStream<CreateLessonPlanEvent> =
        requests.stream(Streams.CREATE_LESSON_PLAN, LingaraJson.encodeToString(LessonPlanCreateRequest.serializer(), body), null)

    /** Rejoins a lesson plan's generation by its [id] (scope `lesson_plans:read`). */
    public suspend fun streamLessonPlan(id: String): EventStream<StreamLessonPlanEvent> =
        requests.stream(Streams.STREAM_LESSON_PLAN, null, id)

    /** Streams the tutor's reply to one turn (scope `tutor:converse`). */
    public suspend fun sendTutorMessage(body: TutorTurnRequest): EventStream<SendTutorMessageEvent> =
        requests.stream(Streams.SEND_TUTOR_MESSAGE, LingaraJson.encodeToString(TutorTurnRequest.serializer(), body), null)

    /** Fetches a lesson plan by its [id] (scope `lesson_plans:read`). */
    public suspend fun getLessonPlan(id: String): ApiResponse<LessonPlan> =
        requests.get("/v1/lesson-plans/" + encodeSegment(id), true, LessonPlan.serializer())

    /** Reports this client's allowance, or its ledger if it is metered (scope `usage:read`). */
    public suspend fun getUsage(): ApiResponse<Usage> = requests.get("/v1/usage", true, Usage.serializer())

    /** Fetches the API's OpenAPI document. It needs no token. */
    public suspend fun getOpenApiDocument(): ApiResponse<JsonObject> = requests.get("/v1/openapi.json", false, JsonObject.serializer())

    /** Fetches the API's AsyncAPI document, which describes its events. It needs no token. */
    public suspend fun getAsyncApiDocument(): ApiResponse<JsonObject> = requests.get("/v1/asyncapi.json", false, JsonObject.serializer())

    /** Lists the API's versions. It needs no token. */
    public suspend fun listApiVersions(): ApiResponse<VersionList> = requests.get("/v1/versions", false, VersionList.serializer())

    /** Describes one API version by its [id]. It needs no token. */
    public suspend fun getApiVersion(id: String): ApiResponse<VersionDetail> =
        requests.get("/v1/versions/" + encodeSegment(id), false, VersionDetail.serializer())

    /**
     * Lists one page of events, oldest first (scope `events:read`): from [cursor], or from [start]
     * (`latest` or `oldest`) without one, only [types] when given, at most [limit] per page. Send
     * its `nextCursor` back as [cursor] to continue; the `events` helper does that for you.
     */
    public suspend fun listEvents(
        cursor: String? = null,
        start: String? = null,
        types: List<String> = emptyList(),
        limit: Int? = null,
    ): ApiResponse<EventPage> = requests.get("/v1/events" + eventsQuery(cursor, start, types, limit), true, EventPage.serializer())

    /**
     * Opens one connection of the event stream (scope `events:read`): K5's stream, which ends on
     * `done` unemitted or throws its `error`. The `tailEvents` helper reconnects (ADR 30.9.26aa D7).
     */
    public suspend fun streamEvents(
        cursor: String? = null,
        start: String? = null,
        types: List<String> = emptyList(),
    ): EventStream<StreamEventsEvent> {
        val path = Streams.STREAM_EVENTS.path + eventsQuery(cursor, start, types, null)
        return requests.open(Streams.STREAM_EVENTS, Call("GET", path, null, "text/event-stream", true))
    }

    /**
     * Sends one event from your game (scope `events:write`, and `lesson_plans:write` when it asks
     * for generation) under [idempotencyKey], or a UUIDv4 generated once for this call: either way
     * the same key on every K4 attempt, so a retry gets the first answer (ADR 30.9.26aa D8). Only a
     * `reaction.planStatus` of `generating` promises a `lesson_plan.*` event.
     */
    public suspend fun sendEvent(
        event: InboundEvent,
        idempotencyKey: String? = null,
    ): ApiResponse<InboundEventAccepted> {
        val call = Call("POST", "/v1/events", event.toJson().toString().toByteArray(), "application/json", true)
        call.headers = mapOf("Idempotency-Key" to (idempotencyKey ?: UUID.randomUUID().toString()))
        return requests.json(call, InboundEventAccepted.serializer())
    }

    override fun toString(): String =
        "LingaraClient(baseUrl=$baseUrl, clientId=$clientId, clientSecret=$secret, version=$version, tokenSource=$tokens)"

    public companion object {
        /** This library's released version, sent in every `User-Agent` (K6). */
        public const val LIBRARY_VERSION: String = BuildInfo.VERSION

        /**
         * The API version this library's models were generated from. A response served under
         * another logs one warning per version id (ADR 30.9.26a).
         */
        public const val GENERATED_FOR_VERSION: String = BuildInfo.GENERATED_FOR_VERSION
    }
}
