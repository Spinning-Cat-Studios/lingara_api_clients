package com.getlingara.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.events.Event;
import com.getlingara.client.events.EventFeed;
import com.getlingara.client.events.EventTail;
import com.getlingara.client.events.EventsRequest;
import com.getlingara.client.events.InboundEvent;
import com.getlingara.client.events.SendEventOptions;
import com.getlingara.client.internal.Deprecations;
import com.getlingara.client.internal.ErrorMapper;
import com.getlingara.client.internal.Pipeline;
import com.getlingara.client.internal.Pipeline.Call;
import com.getlingara.client.internal.Retry;
import com.getlingara.client.internal.SpecVersion;
import com.getlingara.client.internal.Streams;
import com.getlingara.client.internal.UserAgent;
import com.getlingara.client.model.CreateLessonPlanEvent;
import com.getlingara.client.model.DialogueTurnRequest;
import com.getlingara.client.model.EmbedTokenRequest;
import com.getlingara.client.model.EventPage;
import com.getlingara.client.model.GenerateVocabularyEvent;
import com.getlingara.client.model.InboundEventAccepted;
import com.getlingara.client.model.LessonPlan;
import com.getlingara.client.model.LessonPlanCreateRequest;
import com.getlingara.client.model.SendDialogueTurnEvent;
import com.getlingara.client.model.SendTutorMessageEvent;
import com.getlingara.client.model.StreamEventsEvent;
import com.getlingara.client.model.StreamLessonPlanEvent;
import com.getlingara.client.model.TutorTurnRequest;
import com.getlingara.client.model.Usage;
import com.getlingara.client.model.VersionDetail;
import com.getlingara.client.model.VersionList;
import com.getlingara.client.model.VocabRequest;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Lingara API client (ADR 29.9.26r D4). Build one with {@link #builder()}; it is safe for
 * concurrent use and needs no {@code close()}.
 *
 * <p>Every method blocks, which a virtual thread (Java 21) or an executor makes cheap, and answers
 * the thread's interrupt with the JDK's {@link java.util.concurrent.CancellationException}, its
 * flag restored. A stream method sends its request at once and returns an {@link EventStream} when
 * the response headers are in, so a refusal is thrown by the call and an in-stream failure by the
 * stream. Every other failure is a {@link LingaraException}.
 */
public final class LingaraClient {
  /** This library's released version, sent in every {@code User-Agent} (K6). */
  public static final String LIBRARY_VERSION = UserAgent.version();

  /**
   * The API version this library's models were generated from. A response served under another logs
   * one warning per version id (ADR 30.9.26a).
   */
  public static final String GENERATED_FOR_VERSION = SpecVersion.GENERATED_FOR_VERSION;

  private final String baseUrl;
  private final String version;
  private final Retry.Policy policy;
  private final Duration streamIdleTimeout;
  private final TokenSource tokens;
  private final ClientSecret secret;
  private final String clientId;
  private final Pipeline pipeline;
  private final ObjectMapper mapper;
  private final int tailMaxFailures;

  LingaraClient(LingaraClientBuilder options, TokenSource tokens, HttpClient http) {
    this.baseUrl = options.baseUrl.toString().replaceAll("/+$", "");
    this.version = options.version;
    this.policy = options.policy();
    this.streamIdleTimeout = options.streamIdleTimeout;
    this.tokens = tokens;
    this.secret = options.clientSecret;
    this.clientId = options.clientId;
    Pipeline.Target target =
        new Pipeline.Target(
            baseUrl,
            version,
            UserAgent.of(options.userAgentSuffix),
            secret,
            options.requestTimeout);
    this.pipeline =
        new Pipeline(target, policy, tokens, http, new Deprecations(options.onDeprecation));
    this.mapper = pipeline.mapper();
    this.tailMaxFailures = options.tailMaxFailures;
  }

  /** The client's JSON mapper; see {@link Pipeline#jsonMapper()}. */
  static ObjectMapper mapper() {
    return Pipeline.jsonMapper();
  }

  /**
   * Starts a client's options. With no credentials the client can call the four operations that
   * need no token.
   *
   * @return a builder with every default
   */
  public static Builder builder() {
    return new Builder();
  }

  // ── The operations ────────────────────────────────────────────────────────────────────────

  /**
   * Streams a vocabulary list (scope {@code vocab:generate}).
   *
   * @param body the request
   * @return the open stream
   */
  public EventStream<GenerateVocabularyEvent> generateVocabulary(VocabRequest body) {
    return open(Streams.GENERATE_VOCABULARY, body, null, Streams::decodeGenerateVocabulary);
  }

  /**
   * Streams a new lesson plan's generation (scope {@code lesson_plans:write}). A plan served from
   * the library is a lone {@code result}.
   *
   * @param body the request
   * @return the open stream
   */
  public EventStream<CreateLessonPlanEvent> createLessonPlan(LessonPlanCreateRequest body) {
    return open(Streams.CREATE_LESSON_PLAN, body, null, Streams::decodeCreateLessonPlan);
  }

  /**
   * Rejoins a lesson plan's generation by its id (scope {@code lesson_plans:read}).
   *
   * @param id the plan's id
   * @return the open stream
   */
  public EventStream<StreamLessonPlanEvent> streamLessonPlan(String id) {
    return open(Streams.STREAM_LESSON_PLAN, null, id, Streams::decodeStreamLessonPlan);
  }

  /**
   * Streams the tutor's reply to one turn (scope {@code tutor:converse}).
   *
   * @param body the request
   * @return the open stream
   */
  public EventStream<SendTutorMessageEvent> sendTutorMessage(TutorTurnRequest body) {
    return open(Streams.SEND_TUTOR_MESSAGE, body, null, Streams::decodeSendTutorMessage);
  }

  /**
   * Fetches a lesson plan by its id (scope {@code lesson_plans:read}).
   *
   * @param id the plan's id
   * @return the plan
   */
  public ApiResponse<LessonPlan> getLessonPlan(String id) {
    return json("/v1/lesson-plans/" + encodeSegment(id), true, LessonPlan.class);
  }

  /**
   * Reports this client's allowance, or its ledger if it is metered (scope {@code usage:read}).
   *
   * @return the usage
   */
  public ApiResponse<Usage> getUsage() {
    return json("/v1/usage", true, Usage.class);
  }

  /**
   * Fetches the API's OpenAPI document. It needs no token.
   *
   * @return the document
   */
  public ApiResponse<JsonNode> getOpenApiDocument() {
    return json("/v1/openapi.json", false, JsonNode.class);
  }

  /**
   * Lists the API's versions. It needs no token.
   *
   * @return the versions
   */
  public ApiResponse<VersionList> listApiVersions() {
    return json("/v1/versions", false, VersionList.class);
  }

  /**
   * Describes one API version by its id. It needs no token.
   *
   * @param id the version id
   * @return the version
   */
  public ApiResponse<VersionDetail> getApiVersion(String id) {
    return json("/v1/versions/" + encodeSegment(id), false, VersionDetail.class);
  }

  /**
   * Fetches the API's AsyncAPI document, which describes its events. It needs no token.
   *
   * @return the document
   */
  public ApiResponse<JsonNode> getAsyncApiDocument() {
    return json("/v1/asyncapi.json", false, JsonNode.class);
  }

  // ── Embedding (ADR 1.10.26w) ──────────────────────────────────────────────────────────────

  /**
   * Mints a player's embed token (scope {@code embed:mint}, a metered client only). Call it on your
   * server, never on a player's device. The token lives 900 seconds and Lingara never refreshes it:
   * mint again when the player kit asks. Store the result's {@code subject} beside the player.
   *
   * @param body the player's reference, and optionally the scopes and the web origin to bind
   * @return the token, which renders redacted
   * @throws TransportException {@code malformed_response} when any of the six answer fields is
   *     missing or mistyped
   */
  public ApiResponse<MintedToken> createEmbedToken(EmbedTokenRequest body) {
    Call call =
        new Call("POST", "/v1/embed/tokens", pipeline.bytes(body), "application/json", true);
    Pipeline.Answer<JsonNode> answer;
    try {
      answer = pipeline.json(call, JsonNode.class);
    } catch (TransportException e) {
      // A parser's message can quote the body, and so the token: drop the cause.
      throw e.kind() == TransportKind.MALFORMED_RESPONSE
          ? new TransportException(TransportKind.MALFORMED_RESPONSE, null)
          : e;
    }
    return new ApiResponse<>(MintedToken.of(answer.body(), mapper), answer.served());
  }

  /**
   * Deletes a player and revokes its tokens (scope {@code embed:mint}). It is idempotent: an
   * unknown player is a success too, so K4 retries it safely. It keeps working while embedding is
   * switched off for your client.
   *
   * @param playerRef your reference for the player, sent as one encoded path segment
   * @return no body, and the served version
   */
  public ApiResponse<Void> deleteEmbedPlayer(String playerRef) {
    String path = "/v1/embed/players/" + encodeSegment(playerRef);
    Pipeline.Answer<Void> answer =
        pipeline.empty(new Call("DELETE", path, null, "application/json", true));
    return new ApiResponse<>(null, answer.served());
  }

  /**
   * Streams an NPC's reply to one line (scope {@code embed:play}, from a player's embed token or a
   * metered client's own). Each turn is billed, so it is sent once with no K4 retries: a {@code
   * 429} or {@code 503} is thrown at once as an {@link ApiException} carrying its {@code
   * Retry-After}, and sending the turn again is the caller's choice. {@code 403
   * embed_needs_metered} and {@code 422 safety_input_flagged} ("say something else") are never
   * worth retrying.
   *
   * <p>The window is the caller's and is not checked here: at most 12 {@code history} entries,
   * {@code line} and each entry at most 500 characters. Send each NPC reply back into {@code
   * history} cut to its first 500 characters.
   *
   * @param body the turn
   * @return the open stream: {@code delta} and {@code notice}, ending on {@code done}
   */
  public EventStream<SendDialogueTurnEvent> sendDialogueTurn(DialogueTurnRequest body) {
    Streams.Route route = Streams.SEND_DIALOGUE_TURN;
    Call call =
        new Call(
            route.method(),
            route.path(),
            pipeline.bytes(body),
            "text/event-stream",
            true,
            Map.of(),
            true);
    return stream(route, call, Streams::decodeSendDialogueTurn);
  }

  // ── Events (ADR 30.9.26aa) ────────────────────────────────────────────────────────────────

  /**
   * Lists one page of events, oldest first (scope {@code events:read}). Send its {@code
   * next_cursor} back as {@code cursor} to continue; {@link #events} does that for you.
   *
   * @param request the cursor or start, the types and the page size
   * @return the page
   */
  public ApiResponse<EventPage> listEvents(EventsRequest request) {
    String query = query(request.cursor(), request.start(), request.types(), request.limit());
    return json(get("/v1/events" + query, "application/json"), EventPage.class);
  }

  /**
   * Reads every event from {@code request}'s cursor up to now, page by page, each parsed into an
   * {@link Event} (scope {@code events:read}). It never sleeps or polls; see {@link EventFeed}.
   *
   * @param request the cursor, or the start without one, and the types
   * @return the feed, which sends its first request when first iterated
   */
  public EventFeed events(EventsRequest request) {
    return new EventFeed(request, this::listEventsJson);
  }

  /**
   * Opens one connection of the event stream from now, every type (scope {@code events:read}).
   *
   * @return the open stream
   */
  public EventStream<StreamEventsEvent> streamEvents() {
    return streamEvents(EventsRequest.of());
  }

  /**
   * Opens one connection of the event stream (scope {@code events:read}): K5's stream, which ends
   * on {@code done} unyielded or raises its {@code error}. {@link #tailEvents} reconnects.
   *
   * @param request the cursor, start and types, sent as the query
   * @return the open stream
   */
  public EventStream<StreamEventsEvent> streamEvents(EventsRequest request) {
    String query = query(request.cursor(), request.start(), request.types(), null);
    Call call = get(Streams.STREAM_EVENTS.path() + query, "text/event-stream");
    return stream(Streams.STREAM_EVENTS, call, Streams::decodeStreamEvents);
  }

  /**
   * Tails the event stream, reconnecting after every ending from the last {@code id:} seen
   * (CONTRACT.md K5a; scope {@code events:read}). The first open is sent when the stream is first
   * iterated; see {@link EventTail}. Its {@link EventStream#cursor()} hands over to and from {@link
   * #events}.
   *
   * @param request the cursor, sent as {@code Last-Event-ID}, or the start without one, and the
   *     types
   * @return the tail, which ends only when closed or when its failures exceed the bound
   */
  public EventStream<Event> tailEvents(EventsRequest request) {
    // Every reopen repeats the first URL: types, and start only when there was no cursor.
    String start = request.cursor() == null ? request.start() : null;
    String path = Streams.STREAM_EVENTS.path() + query(null, start, request.types(), null);
    EventTail.Backoff backoff =
        new EventTail.Backoff(policy.sleeper(), policy.cap(), tailMaxFailures);
    EventTail tail = new EventTail(request.cursor(), id -> openTail(path, id), backoff);
    return new EventStream<>(new EventStream.Tail<>(tail, tail::cursor, tail::close));
  }

  /**
   * Sends one event from your game (scope {@code events:write}, and {@code lesson_plans:write} when
   * it asks for generation), with a generated {@code Idempotency-Key}.
   *
   * @param event the event
   * @return the accepted event; only {@code reaction.plan_status} {@code generating} promises a
   *     {@code lesson_plan.*} event
   */
  public ApiResponse<InboundEventAccepted> sendEvent(InboundEvent event) {
    return sendEvent(event, SendEventOptions.defaults());
  }

  /**
   * Sends one event under the caller's {@code Idempotency-Key}, or a generated one: either way the
   * same key on every K4 attempt, so a retry gets the first answer (ADR 30.9.26aa D8).
   *
   * @param event the event
   * @param options the key, or none to generate a UUIDv4 once for this call
   * @return the accepted event
   */
  public ApiResponse<InboundEventAccepted> sendEvent(InboundEvent event, SendEventOptions options) {
    String key =
        options.idempotencyKey() != null ? options.idempotencyKey() : UUID.randomUUID().toString();
    ObjectNode body = mapper.createObjectNode().put("type", event.type());
    body.set("data", mapper.valueToTree(event.data()));
    Map<String, String> headers = Map.of("Idempotency-Key", key);
    Call call =
        new Call(
            "POST", "/v1/events", pipeline.bytes(body), "application/json", true, headers, false);
    return json(call, InboundEventAccepted.class);
  }

  @Override
  public String toString() {
    return "LingaraClient{baseUrl="
        + baseUrl
        + ", clientId="
        + clientId
        + ", clientSecret="
        + (secret == null ? null : secret)
        + ", version="
        + version
        + ", tokenSource="
        + tokens
        + "}";
  }

  // ── Over the request pipeline (internal/Pipeline.java) ────────────────────────────────────

  private static Call get(String path, String accept) {
    return Pipeline.get(path, accept);
  }

  private <T> ApiResponse<T> json(String path, boolean needsToken, Class<T> type) {
    return json(new Call("GET", path, null, "application/json", needsToken), type);
  }

  private <T> ApiResponse<T> json(Call call, Class<T> type) {
    Pipeline.Answer<T> answer = pipeline.json(call, type);
    return new ApiResponse<>(answer.body(), answer.served());
  }

  private JsonNode listEventsJson(EventsRequest page) {
    String query = query(page.cursor(), page.start(), page.types(), page.limit());
    return pipeline.json(get("/v1/events" + query, "application/json"), JsonNode.class).body();
  }

  /** One tail open: K1's refresh, but no K4 loop, and the cursor as {@code Last-Event-ID}. */
  private EventStream<Event> openTail(String path, String lastEventId) {
    Map<String, String> headers =
        lastEventId == null ? Map.of() : Map.of("Last-Event-ID", lastEventId);
    Call call = new Call("GET", path, null, "text/event-stream", true, headers, true);
    return stream(Streams.STREAM_EVENTS, call, LingaraClient::tailEvent);
  }

  /** A tail's {@code event} frame, parsed into the union; the other two end the connection. */
  private static Event tailEvent(String event, JsonNode data, ObjectMapper mapper) {
    return "event".equals(event) ? Event.parse(data) : null;
  }

  private <E> EventStream<E> open(
      Streams.Route route, Object body, String id, EventStream.Decoder<E> decoder) {
    String path = route.path().replace("{id}", id == null ? "" : encodeSegment(id));
    byte[] payload = body == null ? null : pipeline.bytes(body);
    Call call = new Call(route.method(), path, payload, "text/event-stream", true);
    return stream(route, call, decoder);
  }

  private <E> EventStream<E> stream(
      Streams.Route route, Call call, EventStream.Decoder<E> decoder) {
    HttpResponse<InputStream> response = pipeline.send(call);
    if (!ErrorMapper.mediaType(response.headers()).equals("text/event-stream")) {
      Retry.discard(response);
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
    }
    Optional<String> served = pipeline.observe(response);
    EventStream.Settings settings = new EventStream.Settings(served, mapper);
    return new EventStream<>(route, decoder, response.body(), streamIdleTimeout, settings);
  }

  /** An events query: each value encoded, and {@code types} one comma-separated value. */
  static String query(String cursor, String start, List<String> types, Integer limit) {
    List<String> pairs = new ArrayList<>();
    if (cursor != null) {
      pairs.add("cursor=" + encodeSegment(cursor));
    }
    if (start != null) {
      pairs.add("start=" + encodeSegment(start));
    }
    if (!types.isEmpty()) {
      String joined =
          types.stream().map(LingaraClient::encodeSegment).collect(Collectors.joining(","));
      pairs.add("types=" + joined);
    }
    if (limit != null) {
      pairs.add("limit=" + limit);
    }
    return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
  }

  /** Percent-encodes everything but {@code A–Z a–z 0–9 - . _ ~}, as the other libraries do. */
  static String encodeSegment(String segment) {
    StringBuilder b = new StringBuilder();
    for (byte c : segment.getBytes(StandardCharsets.UTF_8)) {
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || "-._~".indexOf(c) >= 0) {
        b.append((char) c);
      } else {
        b.append(String.format("%%%02X", c & 0xFF));
      }
    }
    return b.toString();
  }

  /**
   * A client's options: every knob of the contract is one method (ADR 29.9.26r D4). The harness
   * builds every client from these and nothing else.
   */
  public static final class Builder extends LingaraClientBuilder {
    Builder() {}
  }
}
