// The client: its options, the nine operations and the request pipeline
// (CONTRACT.md K1–K6). Every option here is public, and the conformance
// harness builds its clients from these and nothing else.

import { INSPECT, LingaraError, REDACTED, TransportError, errorFromResponse, mediaType } from "./errors.js";
import type { operations } from "./generated/schema.js";
import { STREAMS, type StreamOperation } from "./generated/streams.js";
import type {
  CreateLessonPlanEvent,
  GenerateVocabularyEvent,
  LessonPlanCreateRequest,
  SendTutorMessageEvent,
  StreamLessonPlanEvent,
  TutorTurnRequest,
  VocabRequest,
} from "./models.js";
import { parseRetryAfter, withRetries, withTokenRetry, type RetryPolicy } from "./retry.js";
import { realSleeper, systemClock, type Clock, type Sleeper } from "./seams.js";
import { EventStream, type OpenedStream } from "./stream.js";
import { ClientCredentials, type TokenSource } from "./token.js";
import { fetchOnce, transportFailure, type FetchLike } from "./transport.js";
import { userAgent } from "./userAgent.js";
import { VersionObserver, type DeprecationHook } from "./version.js";

export interface LingaraOptions {
  clientId?: string;
  clientSecret?: string;
  /** `"basic"` (the default) or `"post"` (`client_secret_post`). */
  auth?: "basic" | "post";
  scopes?: readonly string[];
  /** Replaces the built-in client-credentials source. Not with `clientSecret`. */
  tokenSource?: TokenSource;
  baseUrl?: string;
  tokenUrl?: string;
  /** Pins every `/v1` request to this API version. */
  version?: string;
  /** Called once per response under a deprecated version. */
  onDeprecation?: DeprecationHook;
  /** Tries per HTTP request; `1` turns retries off. Default 3. */
  maxAttempts?: number;
  /** Default 60. */
  retryAfterCapSeconds?: number;
  /** Default 120 000. */
  streamIdleTimeoutMs?: number;
  /** Default 30 000. */
  tokenRequestTimeoutMs?: number;
  /** Appended to the `User-Agent` after one space. */
  userAgentSuffix?: string;
  /** A testing seam: epoch milliseconds. */
  clock?: Clock;
  /** A testing seam. */
  sleeper?: Sleeper;
  /** Defaults to `globalThis.fetch`. */
  fetch?: FetchLike;
}

export interface CallOptions {
  signal?: AbortSignal;
}

/** A JSON result, with the `Lingara-Version` echo beside it (non-enumerable). */
export type WithServedVersion<T> = T & { readonly servedVersion?: string };

type JsonOk<Op extends keyof operations> = operations[Op]["responses"][200]["content"]["application/json"];

export const DEFAULT_BASE_URL = "https://api.getlingara.com";

interface Send {
  method: "GET" | "POST";
  path: string;
  body?: unknown;
  accept: string;
  needsToken: boolean;
  signal?: AbortSignal | undefined;
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
  readonly #userAgent: string;
  readonly #fetch: FetchLike;

  constructor(options: LingaraOptions = {}) {
    checkOptions(options);
    this.clientId = options.clientId;
    this.#tokens = options.tokenSource ?? credentialsFrom(options);
    this.#baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/+$/, "");
    this.#version = options.version;
    this.#versions = new VersionObserver(options.onDeprecation);
    this.#policy = {
      maxAttempts: options.maxAttempts ?? 3,
      retryAfterCapSeconds: options.retryAfterCapSeconds ?? 60,
      clock: options.clock ?? systemClock,
      sleeper: options.sleeper ?? realSleeper,
    };
    this.#idleMs = options.streamIdleTimeoutMs ?? 120_000;
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

  // --- JSON ----------------------------------------------------------------

  getLessonPlan(params: { id: string }, options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getLessonPlan">>> {
    return this.#json(`/v1/lesson-plans/${encodeURIComponent(params.id)}`, true, options);
  }

  getUsage(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getUsage">>> {
    return this.#json("/v1/usage", true, options);
  }

  getOpenApiDocument(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getOpenApiDocument">>> {
    return this.#json("/v1/openapi.json", false, options);
  }

  listApiVersions(options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"listApiVersions">>> {
    return this.#json("/v1/versions", false, options);
  }

  getApiVersion(params: { id: string }, options: CallOptions = {}): Promise<WithServedVersion<JsonOk<"getApiVersion">>> {
    return this.#json(`/v1/versions/${encodeURIComponent(params.id)}`, false, options);
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

  #stream<E extends { event: string }>(op: StreamOperation, input: { body?: unknown; id?: string }, options: CallOptions): EventStream<E> {
    const route = STREAMS[op];
    const path = input.id === undefined ? route.path : route.path.replace("{id}", encodeURIComponent(input.id));
    const open = (signal: AbortSignal) => this.#openStream({ method: route.method, path, body: input.body, accept: "text/event-stream", needsToken: true, signal });
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

  async #json<T>(path: string, needsToken: boolean, options: CallOptions): Promise<WithServedVersion<T>> {
    const res = await this.#send({ method: "GET", path, accept: "application/json", needsToken, signal: options.signal });
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

  /** Auth, retries and error mapping; resolves with a 2xx response. */
  async #send(req: Send): Promise<Response> {
    const url = this.#baseUrl + req.path;
    const attempt = (token?: string) =>
      withRetries(this.#policy, req.signal, () =>
        fetchOnce({ fetch: this.#fetch, url, init: this.#init(req, token), secrets: [token] }),
      );
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
    const headers: Record<string, string> = { accept: req.accept, "user-agent": this.#userAgent };
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

function checkOptions(options: LingaraOptions): void {
  if (options.tokenSource && options.clientSecret !== undefined) {
    throw new LingaraError("pass either tokenSource or clientSecret, not both");
  }
  if (options.version === "") throw new LingaraError("version must not be empty");
  if (options.clientSecret !== undefined && (globalThis as { document?: unknown }).document !== undefined) {
    throw new LingaraError(
      "a client secret must not be used in a browser: anyone who loads the page can read it. Call the Lingara API from your server.",
    );
  }
  if ((options.clientSecret === undefined) !== (options.clientId === undefined) && !options.tokenSource) {
    throw new LingaraError("clientId and clientSecret go together");
  }
}

function credentialsFrom(options: LingaraOptions): TokenSource | undefined {
  const { clientId, clientSecret } = options;
  if (clientId === undefined || clientSecret === undefined) return undefined;
  // The source shares the client's credentials, auth, scopes, token URL,
  // retry knobs, seams and fetch; it applies the same defaults.
  return new ClientCredentials({ ...options, clientId, clientSecret });
}
