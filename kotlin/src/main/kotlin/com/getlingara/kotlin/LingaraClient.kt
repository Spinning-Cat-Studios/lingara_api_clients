package com.getlingara.kotlin

import com.getlingara.kotlin.internal.BuildInfo
import com.getlingara.kotlin.internal.Call
import com.getlingara.kotlin.internal.Deprecations
import com.getlingara.kotlin.internal.ErrorMapper
import com.getlingara.kotlin.internal.LingaraJson
import com.getlingara.kotlin.internal.Pipeline
import com.getlingara.kotlin.internal.Retry
import com.getlingara.kotlin.internal.Streams
import com.getlingara.kotlin.internal.Target
import com.getlingara.kotlin.internal.UserAgent
import com.getlingara.kotlin.model.CreateLessonPlanEvent
import com.getlingara.kotlin.model.GenerateVocabularyEvent
import com.getlingara.kotlin.model.LessonPlan
import com.getlingara.kotlin.model.LessonPlanCreateRequest
import com.getlingara.kotlin.model.SendTutorMessageEvent
import com.getlingara.kotlin.model.StreamLessonPlanEvent
import com.getlingara.kotlin.model.TutorTurnRequest
import com.getlingara.kotlin.model.Usage
import com.getlingara.kotlin.model.VersionDetail
import com.getlingara.kotlin.model.VersionList
import com.getlingara.kotlin.model.VocabRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import java.net.http.HttpClient
import java.net.http.HttpResponse
import kotlin.time.Duration

/**
 * Builds a client (ADR 29.9.26s D4). With no `clientCredentials` the client can call the three
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
    private val streamIdleTimeout: Duration = options.streamIdleTimeout
    private val deprecations = Deprecations(options.onDeprecation)
    private val pipeline =
        Pipeline(Target(baseUrl, version, UserAgent.of(options.userAgentSuffix), secret), options.policy(), tokens, http)

    /** Streams a vocabulary list (scope `vocab:generate`). */
    public suspend fun generateVocabulary(body: VocabRequest): EventStream<GenerateVocabularyEvent> =
        open(Streams.GENERATE_VOCABULARY, LingaraJson.encodeToString(VocabRequest.serializer(), body), null)

    /** Streams a new lesson plan's generation (scope `lesson_plans:write`). */
    public suspend fun createLessonPlan(body: LessonPlanCreateRequest): EventStream<CreateLessonPlanEvent> =
        open(Streams.CREATE_LESSON_PLAN, LingaraJson.encodeToString(LessonPlanCreateRequest.serializer(), body), null)

    /** Rejoins a lesson plan's generation by its [id] (scope `lesson_plans:read`). */
    public suspend fun streamLessonPlan(id: String): EventStream<StreamLessonPlanEvent> = open(Streams.STREAM_LESSON_PLAN, null, id)

    /** Streams the tutor's reply to one turn (scope `tutor:converse`). */
    public suspend fun sendTutorMessage(body: TutorTurnRequest): EventStream<SendTutorMessageEvent> =
        open(Streams.SEND_TUTOR_MESSAGE, LingaraJson.encodeToString(TutorTurnRequest.serializer(), body), null)

    /** Fetches a lesson plan by its [id] (scope `lesson_plans:read`). */
    public suspend fun getLessonPlan(id: String): ApiResponse<LessonPlan> =
        json("/v1/lesson-plans/" + encodeSegment(id), true, LessonPlan.serializer())

    /** Reports this client's allowance, or its ledger if it is metered (scope `usage:read`). */
    public suspend fun getUsage(): ApiResponse<Usage> = json("/v1/usage", true, Usage.serializer())

    /** Fetches the API's OpenAPI document. It needs no token. */
    public suspend fun getOpenApiDocument(): ApiResponse<JsonObject> = json("/v1/openapi.json", false, JsonObject.serializer())

    /** Lists the API's versions. It needs no token. */
    public suspend fun listApiVersions(): ApiResponse<VersionList> = json("/v1/versions", false, VersionList.serializer())

    /** Describes one API version by its [id]. It needs no token. */
    public suspend fun getApiVersion(id: String): ApiResponse<VersionDetail> =
        json("/v1/versions/" + encodeSegment(id), false, VersionDetail.serializer())

    override fun toString(): String =
        "LingaraClient(baseUrl=$baseUrl, clientId=$clientId, clientSecret=$secret, version=$version, tokenSource=$tokens)"

    private suspend fun <T> json(
        path: String,
        needsToken: Boolean,
        serializer: KSerializer<T>,
    ): ApiResponse<T> {
        val call = Call("GET", path, null, "application/json", needsToken)
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

    private suspend fun <E : Any> open(
        route: Streams.Route<E>,
        body: String?,
        id: String?,
    ): EventStream<E> {
        val path = route.path.replace("{id}", id?.let(::encodeSegment) ?: "")
        val call = Call(route.method, path, body?.toByteArray(), "text/event-stream", true)
        val response = pipeline.send(call, HttpResponse.BodyHandlers.ofInputStream())
        if (ErrorMapper.mediaType(response.headers()) != "text/event-stream") {
            Retry.discard(response)
            throw TransportException(TransportKind.MALFORMED_RESPONSE, null)
        }
        val served = deprecations.observe(response.headers(), response.request().uri())
        return EventStream(route, response.body(), streamIdleTimeout, served)
    }

    public companion object {
        /** This library's released version, sent in every `User-Agent` (K6). */
        public const val LIBRARY_VERSION: String = BuildInfo.VERSION

        /**
         * The API version this library's models were generated from. A response served under
         * another logs one warning per version id (ADR 30.9.26a).
         */
        public const val GENERATED_FOR_VERSION: String = BuildInfo.GENERATED_FOR_VERSION

        /** Percent-encodes everything but `A–Z a–z 0–9 - . _ ~`, as the other libraries do. */
        internal fun encodeSegment(segment: String): String =
            segment.toByteArray().joinToString("") { byte ->
                val c = byte.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") c.toString() else "%%%02X".format(byte.toInt() and 0xFF)
            }
    }
}
