package com.getlingara.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import com.getlingara.client.internal.Retry;
import com.getlingara.client.internal.SpecVersion;
import com.getlingara.client.internal.Streams;
import com.getlingara.client.internal.UserAgent;
import com.getlingara.client.model.CreateLessonPlanEvent;
import com.getlingara.client.model.EventPage;
import com.getlingara.client.model.GenerateVocabularyEvent;
import com.getlingara.client.model.InboundEventAccepted;
import com.getlingara.client.model.LessonPlan;
import com.getlingara.client.model.LessonPlanCreateRequest;
import com.getlingara.client.model.SendTutorMessageEvent;
import com.getlingara.client.model.StreamEventsEvent;
import com.getlingara.client.model.StreamLessonPlanEvent;
import com.getlingara.client.model.TutorTurnRequest;
import com.getlingara.client.model.Usage;
import com.getlingara.client.model.VersionDetail;
import com.getlingara.client.model.VersionList;
import com.getlingara.client.model.VocabRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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

  private static final int REFUSAL_BODY_BYTES = 64 * 1024;

  private final HttpClient http;
  private final String baseUrl;
  private final String version;
  private final String userAgent;
  private final Retry.Policy policy;
  private final Duration streamIdleTimeout;
  private final Duration requestTimeout;
  private final TokenSource tokens;
  private final ClientSecret secret;
  private final String clientId;
  private final Deprecations deprecations;
  private final ObjectMapper mapper;
  private final int tailMaxFailures;

  LingaraClient(LingaraClientBuilder options, TokenSource tokens, HttpClient http) {
    this.http = http;
    this.baseUrl = options.baseUrl.toString().replaceAll("/+$", "");
    this.version = options.version;
    this.userAgent = UserAgent.of(options.userAgentSuffix);
    this.policy = options.policy();
    this.streamIdleTimeout = options.streamIdleTimeout;
    this.requestTimeout = options.requestTimeout;
    this.tokens = tokens;
    this.secret = options.clientSecret;
    this.clientId = options.clientId;
    this.deprecations = new Deprecations(options.onDeprecation);
    this.mapper = mapper();
    this.tailMaxFailures = options.tailMaxFailures;
  }

  /**
   * The client's JSON mapper. An unknown response field is ignored, since fields are additive. A
   * request leaves out a null field and an empty optional list: {@code TutorTurnRequest.history}
   * defaults to an empty list the caller never set. A required field's own {@code
   * JsonInclude(ALWAYS)} still wins over both.
   */
  static ObjectMapper mapper() {
    ObjectMapper mapper =
        new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setDefaultPropertyInclusion(
                JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
    mapper
        .configOverride(List.class)
        .setInclude(
            JsonInclude.Value.construct(
                JsonInclude.Include.NON_EMPTY, JsonInclude.Include.NON_NULL));
    return mapper;
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

  // ── The thirteen operations ───────────────────────────────────────────────────────────────

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
        new Call("POST", "/v1/events", bytes(body), "application/json", true, headers, false);
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

  // ── The request pipeline ──────────────────────────────────────────────────────────────────

  /**
   * One {@code /v1} request, before auth: {@code headers} are its own ({@code Idempotency-Key},
   * {@code Last-Event-ID}), and {@code once} sends it with no K4 loop, as a tail open does (K5a).
   */
  private record Call(
      String method,
      String path,
      byte[] body,
      String accept,
      boolean needsToken,
      Map<String, String> headers,
      boolean once) {
    Call(String method, String path, byte[] body, String accept, boolean needsToken) {
      this(method, path, body, accept, needsToken, Map.of(), false);
    }
  }

  private static Call get(String path, String accept) {
    return new Call("GET", path, null, accept, true);
  }

  private <T> ApiResponse<T> json(String path, boolean needsToken, Class<T> type) {
    return json(new Call("GET", path, null, "application/json", needsToken), type);
  }

  private <T> ApiResponse<T> json(Call call, Class<T> type) {
    HttpResponse<InputStream> response = send(call);
    Optional<String> served = deprecations.observe(response.headers(), response.request().uri());
    byte[] body;
    try (InputStream in = response.body()) {
      body = in.readAllBytes();
    } catch (IOException e) {
      throw failedRead(e);
    }
    try {
      return new ApiResponse<>(mapper.readValue(body, type), served);
    } catch (IOException e) {
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, e);
    }
  }

  private JsonNode listEventsJson(EventsRequest page) {
    String query = query(page.cursor(), page.start(), page.types(), page.limit());
    return json(get("/v1/events" + query, "application/json"), JsonNode.class).body();
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
    byte[] payload = body == null ? null : bytes(body);
    Call call = new Call(route.method(), path, payload, "text/event-stream", true);
    return stream(route, call, decoder);
  }

  private <E> EventStream<E> stream(
      Streams.Route route, Call call, EventStream.Decoder<E> decoder) {
    HttpResponse<InputStream> response = send(call);
    if (!ErrorMapper.mediaType(response.headers()).equals("text/event-stream")) {
      Retry.discard(response);
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
    }
    Optional<String> served = deprecations.observe(response.headers(), response.request().uri());
    EventStream.Settings settings = new EventStream.Settings(served, mapper);
    return new EventStream<>(route, decoder, response.body(), streamIdleTimeout, settings);
  }

  /** Auth and the one 401 retry, K4's loop, and the refusal mapping: returns a 2xx. */
  private HttpResponse<InputStream> send(Call call) {
    HttpResponse<InputStream> response = authorised(call);
    int status = response.statusCode();
    if (status >= 200 && status <= 299) {
      return response;
    }
    byte[] body;
    try (InputStream in = response.body()) {
      body = in.readNBytes(REFUSAL_BODY_BYTES);
    } catch (IOException e) {
      body = new byte[0];
    }
    throw ErrorMapper.refusal(
        ErrorMapper.Endpoint.V1, status, response.headers(), body, policy.clock().instant());
  }

  /**
   * K1's one 401 retry: on a 401, forget that token (only if it is still cached), get another and
   * send once more, with a fresh K4 budget. A client with no token source sends an operation that
   * needs one without {@code Authorization}, and the server's 401 is the answer.
   */
  private HttpResponse<InputStream> authorised(Call call) {
    if (tokens == null || !call.needsToken()) {
      return attempts(call, null);
    }
    AccessToken first = tokens.token();
    HttpResponse<InputStream> response = attempts(call, first);
    if (response.statusCode() != 401) {
      return response;
    }
    Retry.discard(response);
    tokens.invalidate(first);
    return attempts(call, tokens.token());
  }

  private HttpResponse<InputStream> attempts(Call call, AccessToken token) {
    if (call.once()) {
      return sendOnce(call, token);
    }
    return Retry.withRetries(policy, () -> sendOnce(call, token));
  }

  private HttpResponse<InputStream> sendOnce(Call call, AccessToken token) {
    try {
      return http.send(request(call, token), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw Retry.cancelled();
    } catch (IOException e) {
      if (Thread.currentThread().isInterrupted()) {
        throw Retry.cancelled();
      }
      throw ErrorMapper.transport(e, false, secrets(token));
    }
  }

  private HttpRequest request(Call call, AccessToken token) {
    HttpRequest.BodyPublisher publisher =
        call.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(call.body());
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + call.path()))
            .method(call.method(), publisher)
            .header("Accept", call.accept())
            .header("User-Agent", userAgent);
    if (call.body() != null) {
      builder.header("Content-Type", "application/json");
    }
    if (token != null) {
      builder.header("Authorization", "Bearer " + token.exposeSecret());
    }
    if (version != null) {
      builder.header("Lingara-Version", version);
    }
    call.headers().forEach(builder::header);
    if (requestTimeout != null) {
      builder.timeout(requestTimeout);
    }
    return builder.build();
  }

  private RuntimeException failedRead(IOException failure) {
    if (Thread.currentThread().isInterrupted()) {
      return Retry.cancelled();
    }
    return ErrorMapper.transport(failure, true, secrets(null));
  }

  private List<String> secrets(AccessToken token) {
    List<String> out = new ArrayList<>();
    if (token != null) {
      out.add(token.exposeSecret());
    }
    if (secret != null) {
      out.add(secret.exposeSecret());
    }
    return out;
  }

  private byte[] bytes(Object body) {
    try {
      return mapper.writeValueAsBytes(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("the request body cannot be encoded as JSON", e);
    }
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
