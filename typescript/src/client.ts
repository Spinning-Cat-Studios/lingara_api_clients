// The client: the sixteen operations, the event helpers and the request
// pipeline (CONTRACT.md K1–K6, K5a). Its options live in options.ts.

import { mintedToken, type MintedToken } from "./embed.js";
import { INSPECT, LingaraError, REDACTED, TransportError, errorFromResponse, mediaType } from "./errors.js";
import { EventFeed, eventsQuery, type EventsParams, type ListEventsParams } from "./events/feed.js";
import { sendEventRequest, type SendEventOptions } from "./events/send.js";
import { EventTail } from "./events/tail.js";
import type { InboundEvent } from "./generated/events.js";
import type { operations } from "./generated/schema.js";
import { STREAMS, type StreamOperation } from "./generated/streams.js";
import type {
  CreateLessonPlanEvent,
  DialogueTurnRequest,
  EmbedTokenRequest,
  GenerateVocabularyEvent,
  InboundEventAccepted,
  LessonPlanCreateRequest,
  SendDialogueTurnEvent,
  SendTutorMessageEvent,
  StreamEventsEvent,
  StreamLessonPlanEvent,
  TutorTurnRequest,
  VocabRequest,
} from "./models.js";
import { checkOptions, credentialsFrom, retryPolicy, type CallOptions, type LingaraOptions } from "./options.js";
import { parseRetryAfter, withRetries, withTokenRetry, type RetryPolicy } from "./retry.js";
import { EventStream, type OpenedStream } from "./stream.js";
import { ClientCredentials, type TokenSource } from "./token.js";
import { encodeSegment, fetchOnce, transportFailure, type FetchLike } from "./transport.js";
import { userAgent } from "./userAgent.js";
import { VersionObserver } from "./version.js";

export type { CallOptions, LingaraOptions } from "./options.js";

/** A JSON result, with the `Lingara-Version` echo beside it (non-enumerable). */
export type WithServedVersion<T> = T & { readonly servedVersion?: string };

type JsonOk<Op extends keyof operations> = operations[Op]["responses"] extends { 200: { content: { "application/json": infer T } } } ? T : never;

export const DEFAULT_BASE_URL = "https://api.getlingara.com";

/** A bodiless answer's result: only the `Lingara-Version` echo. */
type NoContent = { readonly servedVersion?: string };

interface Send {
  method: "GET" | "POST" | "DELETE";
  path: string;
  body?: unknown;
  accept: string;
  needsToken: boolean;
  headers?: Record<string, string> | undefined;
  /** `false` bypasses K4's attempt loop: the tail counts its own (K5a). */
  retries?: boolean;
  signal?: AbortSignal | undefined;
}

/** A stream request's parts beyond its route. */
interface StreamInput {
  body?: unknown;
  id?: string;
  query?: string;
  headers?: Record<string, string>;
  retries?: boolean;
}

/** The Lingara API. Server-side only: Node, Deno and Bun. */
export class Lingara {
  readonly clientId: string | undefined;
  readonly #tokens: TokenSource | undefined;
  readonly #baseUrl: string;
  readonly #version: string | undefined;
  readonly #versions: VersionObserver;
  readonly #policy: RetryPolicy;
  readonly #idleMs: number;
  readonly #tailMaxFailures: number;
  readonly #userAgent: string;
  readonly #fetch: FetchLike;

  constructor(options: LingaraOptions = {}) {
    checkOptions(options);
    this.clientId = options.clientId;
    this.#tokens = options.tokenSource ?? credentialsFrom(options);
    this.#baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
    this.#version = options.version;
    this.#versions = new VersionObserver(options.onDeprecation);
    this.#policy = retryPolicy(options);
    this.#idleMs = options.streamIdleTimeoutMs ?? 120_000;
    this.#tailMaxFailures = options.tailMaxFailures ?? 8;
    this.#userAgent = userAgent(options.userAgentSuffix);
    this.#fetch = options.fetch ?? ((input, init) => globalThis.fetch(input, init));
  }

  /** The token source in use, if the client has credentials. */
  get tokenSource(): TokenSource | undefined {
    return this.#tokens;
  }

  // --- streams -------------------------------------------------------------

  generateVocabulary(body: VocabRequest, options: CallOptions = {}): EventStream<GenerateVocabularyEvent> {
    return this.#stream("generateVocabulary", { body }, options);
  }

  createLessonPlan(body: LessonPlanCreateRequest, options: CallOptions = {}): EventStream<CreateLessonPlanEvent> {
    return this.#stream("createLessonPlan", { body }, options);
  }

  streamLessonPlan(params: { id: string }, options: CallOptions = {}): EventStream<StreamLessonPlanEvent> {
    return this.#stream("streamLessonPlan", { id: params.id }, options);
  }

  sendTutorMessage(body: TutorTurnRequest, options: CallOptions = {}): EventStream<SendTutorMessageEvent> {
    return this.#stream("sendTutorMessage", { body }, options);
  }

  /** One connection under K5; `tailEvents` is the one that reconnects. */
  streamEvents(params: EventsParams & { lastEventId?: string } = {}, options: CallOptions = {}): EventStream<StreamEventsEvent> {
    const headers: Record<string, string> = params.lastEventId === undefined ? {} : { "last-event-id": params.lastEventId };
    return this.#stream("streamEvents", { query: eventsQuery(params), headers }, options);
  }

  // --- events (ADR 30.9.26aa) ----------------------------------------------

  /** Every event from `cursor` (or `start`) to where the feed is caught up; never polls. */
  events(params: EventsParams = {}, options: CallOptions = {}): EventFeed {
    return new EventFeed(params, (query) => this.listEvents(query, options));
  }

  /** Live events, reconnecting from `cursor` after every ending (CONTRACT.md K5a). */
  tailEvents(params: EventsParams = {}, options: CallOptions = {}): EventTail {
    // The cursor goes in `Last-Event-ID`, never the query; `start` only without one.
    const query = eventsQuery(params.cursor === undefined ? { start: params.start, types: params.types } : { types: params.types });
    return new EventTail({
      open: (cursor, signal) => {
        const headers: Record<string, string> = cursor === undefined ? {} : { "last-event-id": cursor };
        return this.#stream("streamEvents", { query, headers, retries: false }, { signal });
      },
      cursor: params.cursor,
      sleeper: this.#policy.sleeper,
      retryAfterCapSeconds: this.#policy.retryAfterCapSeconds,
      maxFailures: this.#tailMaxFailures,
      signal: options.signal,
    });
  }

  /** `{type, data}` with one `Idempotency-Key` across K4's attempts; the `202` body. */
  sendEvent(event: InboundEvent, options: SendEventOptions = {}): Promise<WithServedVersion<InboundEventAccepted>> {
    const { body, headers } = sendEventRequest(event, options);
    return this.#json("/v1/events", true, options, { method: "POST", body, headers });
  }

  // --- embedding (ADR 1.10.26w) --------------------------------------------

  /**
   * Mints a token for one player (`embed:mint`, a metered client only), on your server, never on the
   * player's device. Lingara never refreshes it: mint again. A `403 insufficient_scope` or
   * `embed_needs_metered` is raised as an `ApiError`. K4 applies; two mints for one player are harmless.
   */
  async createEmbedToken(body: EmbedTokenRequest, options: CallOptions = {}): Promise<WithServedVersion<MintedToken>> {
    return mintedToken(await this.#json<unknown>("/v1/embed/tokens", true, options, { method: "POST", body }));
  }

  /**
   * Deletes a player and revokes their tokens (`embed:mint`); `playerRef` is one encoded path segment.
   * An unknown player is still a success, so K4's retries are safe, and it works while embedding is off.
   */
  deleteEmbedPlayer(params: { playerRef: string }, options: CallOptions = {}): Promise<NoContent> {
    return this.#noContent("DELETE", `/v1/embed/players/${encodeSegment(params.playerRef)}`, options);
  }

  /**
   * An NPC's reply to one line (`embed:play`): `delta` and `notice`, ending on `done`. The window is
   * yours: at most 12 `history` entries, `line` and each entry at most 500 characters, no total cap;
   * send each NPC reply back cut to its first 500 characters. Never retried, since each attempt spends
   * NPC and metered cells: a 429 or 503 is raised at once as an `ApiError` with its `retryAfter`. No
   * retry helps `403 embed_needs_metered` or `422 safety_input_flagged` (say something else).
   */
  sendDialogueTurn(body: DialogueTurnRequest, options: CallOptions = {}): EventStream<SendDialogueTurnEvent> {
    return this.#stream("sendDialogueTurn", { body, retries: false }, options);
  }

  // --- JSON ----------------------------------------------------------------

  getLessonPlan(params: { id: string }, options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getLessonPlan">>> {
    return this.#json(`/v1/lesson-plans/${encodeSegment(params.id)}`, true, options);
  }

  getUsage(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getUsage">>> {
    return this.#json("/v1/usage", true, options);
  }

  /** One page of events; `events()` walks them. */
  listEvents(params: ListEventsParams = {}, options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"listEvents">>> {
    return this.#json(`/v1/events${eventsQuery(params)}`, true, options);
  }

  getOpenApiDocument(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getOpenApiDocument">>> {
    return this.#json("/v1/openapi.json", false, options);
  }

  getAsyncApiDocument(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getAsyncApiDocument">>> {
    return this.#json("/v1/asyncapi.json", false, options);
  }

  listApiVersions(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"listApiVersions">>> {
    return this.#json("/v1/versions", false, options);
  }

  getApiVersion(params: { id: string }, options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getApiVersion">>> {
    return this.#json(`/v1/versions/${encodeSegment(params.id)}`, false, options);
  }

  // --- rendering: the secret and tokens never appear ------------------------

  toJSON(): Record<string, unknown> {
    const secret = this.#tokens instanceof ClientCredentials ? REDACTED : undefined;
    return { clientId: this.clientId, clientSecret: secret, baseUrl: this.#baseUrl, version: this.#version };
  }

  [INSPECT](): string {
    return `Lingara ${JSON.stringify(this.toJSON())}`;
  }

  toString(): string {
    return this[INSPECT]();
  }

  // --- the pipeline --------------------------------------------------------

  #stream<E extends { event: string }>(op: StreamOperation, input: StreamInput, options: CallOptions): EventStream<E> {
    const route = STREAMS[op];
    const path = (input.id === undefined ? route.path : route.path.replace("{id}", encodeSegment(input.id))) + (input.query ?? "");
    const req = { method: route.method, path, body: input.body, accept: "text/event-stream", needsToken: true, headers: input.headers };
    const open = (signal: AbortSignal) => this.#openStream({ ...req, retries: input.retries ?? true, signal });
    return new EventStream<E>({ operation: op, open, signal: options.signal, idleTimeoutMs: this.#idleMs });
  }

  async #openStream(req: Send): Promise<OpenedStream> {
    const res = await this.#send(req);
    if (mediaType(res) !== "text/event-stream") {
      await res.body?.cancel().catch(() => undefined);
      throw new TransportError("malformed_response");
    }
    const servedVersion = this.#versions.observe(res, this.#baseUrl + req.path);
    return { response: res, servedVersion };
  }

  async #json<T>(path: string, needsToken: boolean, options: CallOptions, extra: Partial<Send> = {}): Promise<WithServedVersion<T>> {
    const res = await this.#send({ method: "GET", path, accept: "application/json", needsToken, signal: options.signal, ...extra });
    const servedVersion = this.#versions.observe(res, this.#baseUrl + path);
    let body: unknown;
    try {
      body = await res.json();
    } catch (err) {
      if (err instanceof SyntaxError) throw new TransportError("malformed_response");
      throw transportFailure(err, options.signal, "body", []);
    }
    if (servedVersion !== undefined && typeof body === "object" && body !== null) {
      Object.defineProperty(body, "servedVersion", { value: servedVersion, enumerable: false });
    }
    return body as WithServedVersion<T>;
  }

  /** A success with no body (a `204`): any 2xx body is discarded unread, the echo still observed. */
  async #noContent(method: Send["method"], path: string, options: CallOptions): Promise<NoContent> {
    const res = await this.#send({ method, path, accept: "application/json", needsToken: true, signal: options.signal });
    const servedVersion = this.#versions.observe(res, this.#baseUrl + path);
    await res.body?.cancel().catch(() => undefined);
    return servedVersion === undefined ? {} : { servedVersion };
  }

  /** Auth, retries and error mapping; resolves with a 2xx response. */
  async #send(req: Send): Promise<Response> {
    const url = this.#baseUrl + req.path;
    const once = (token?: string) => fetchOnce({ fetch: this.#fetch, url, init: this.#init(req, token), secrets: [token] });
    const attempt = req.retries === false ? once : (token?: string) => withRetries(this.#policy, req.signal, () => once(token));
    let res: Response;
    if (!req.needsToken) res = await attempt();
    else if (this.#tokens) res = await withTokenRetry(this.#tokens, req.signal, attempt);
    else throw new LingaraError("this operation needs credentials: construct the client with clientId and clientSecret");
    if (res.ok) return res;
    const retryAfter = parseRetryAfter(res.headers.get("retry-after"), this.#policy.clock);
    const servedVersion = res.headers.get("lingara-version") ?? undefined;
    throw await errorFromResponse(res, { endpoint: "v1", retryAfter, servedVersion });
  }

  #init(req: Send, token: string | undefined): RequestInit {
    const headers: Record<string, string> = { accept: req.accept, "user-agent": this.#userAgent, ...req.headers };
    if (token !== undefined) headers["authorization"] = `Bearer ${token}`;
    if (this.#version !== undefined) headers["lingara-version"] = this.#version;
    const init: RequestInit = { method: req.method, headers };
    if (req.body !== undefined) {
      headers["content-type"] = "application/json";
      init.body = JSON.stringify(req.body);
    }
    if (req.signal) init.signal = req.signal;
    return init;
  }
}
