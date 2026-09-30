// One error family (CONTRACT.md K3): a base class and four variants, the
// mapping from a response to one of them, and the mapping from a failed
// `fetch` to a transport kind.
//
// A dual package can be loaded twice in one process (an ESM importer beside a
// CJS requirer), which makes two copies of every class. Each class therefore
// carries a `Symbol.for` brand and checks it in `Symbol.hasInstance`, so
// `instanceof` holds across the two copies.

export const REDACTED = "[REDACTED]";
export const INSPECT = Symbol.for("nodejs.util.inspect.custom");

function brand(name: string): symbol {
  return Symbol.for(`lingara.error.${name}`);
}

function mark(target: object, name: string): void {
  Object.defineProperty(target, brand(name), { value: true, enumerable: false });
}

function hasBrand(value: unknown, name: string): boolean {
  return typeof value === "object" && value !== null && (value as Record<symbol, unknown>)[brand(name)] === true;
}

/** The base of every error this library raises. */
export class LingaraError extends Error {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return hasBrand(value, "LingaraError");
  }

  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = new.target.name;
    mark(this, "LingaraError");
  }

  /** The fields a caller reads, for `JSON.stringify` and inspection. */
  toJSON(): Record<string, unknown> {
    return { name: this.name, message: this.message, ...this.fields() };
  }

  [INSPECT](): string {
    return `${this.name}: ${this.message} ${JSON.stringify(this.fields())}`;
  }

  protected fields(): Record<string, unknown> {
    return {};
  }
}

export interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  retryAfter?: number | undefined;
  planId?: string | undefined;
  servedVersion?: string | undefined;
}

/** A `/v1` refusal, or a stream's `error` event (then `status` is 200). */
export class ApiError extends LingaraError {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return hasBrand(value, "ApiError");
  }

  readonly status: number;
  readonly code: string;
  readonly retryAfter?: number;
  readonly planId?: string;
  readonly servedVersion?: string;

  constructor(init: ApiErrorInit) {
    super(init.message);
    mark(this, "ApiError");
    this.status = init.status;
    this.code = init.code;
    if (init.retryAfter !== undefined) this.retryAfter = init.retryAfter;
    if (init.planId !== undefined) this.planId = init.planId;
    if (init.servedVersion !== undefined) this.servedVersion = init.servedVersion;
  }

  protected override fields(): Record<string, unknown> {
    const { status, code, retryAfter, planId, servedVersion } = this;
    return { status, code, retryAfter, planId, servedVersion };
  }
}

export interface OAuthErrorInit {
  status: number;
  error: string;
  description?: string | undefined;
  retryAfter?: number | undefined;
}

/** A token-endpoint refusal (RFC 6749 §5.2, or `http_<status>`). */
export class OAuthError extends LingaraError {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return hasBrand(value, "OAuthError");
  }

  readonly status: number;
  readonly error: string;
  readonly description?: string;
  readonly retryAfter?: number;

  constructor(init: OAuthErrorInit) {
    super(init.description ? `${init.error}: ${init.description}` : init.error);
    mark(this, "OAuthError");
    this.status = init.status;
    this.error = init.error;
    if (init.description !== undefined) this.description = init.description;
    if (init.retryAfter !== undefined) this.retryAfter = init.retryAfter;
  }

  protected override fields(): Record<string, unknown> {
    const { status, error, description, retryAfter } = this;
    return { status, error, description, retryAfter };
  }
}

/** A 503 whose body is not JSON: the service is in maintenance. */
export class MaintenanceError extends LingaraError {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return hasBrand(value, "MaintenanceError");
  }

  /** The response text, at most 1 KiB. */
  readonly body: string;
  readonly retryAfter?: number;

  constructor(body: string, retryAfter?: number) {
    super("the Lingara API is under maintenance");
    mark(this, "MaintenanceError");
    this.body = body;
    if (retryAfter !== undefined) this.retryAfter = retryAfter;
  }

  protected override fields(): Record<string, unknown> {
    return { body: this.body, retryAfter: this.retryAfter };
  }
}

export type TransportKind =
  | "connect"
  | "tls"
  | "reset"
  | "timeout"
  | "stream_ended_early"
  | "malformed_response"
  | "malformed_event";

/** No usable HTTP answer. `cause` is the runtime's error, redacted. */
export class TransportError extends LingaraError {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return hasBrand(value, "TransportError");
  }

  readonly kind: TransportKind;

  constructor(kind: TransportKind, cause?: unknown) {
    super(`transport failure: ${kind}`, cause === undefined ? undefined : { cause });
    mark(this, "TransportError");
    this.kind = kind;
  }

  protected override fields(): Record<string, unknown> {
    return { kind: this.kind };
  }
}

// Response → error
const MAINTENANCE_BODY_BYTES = 1024;

export interface ResponseContext {
  endpoint: "v1" | "token";
  retryAfter?: number | undefined;
  servedVersion?: string | undefined;
}

/** Maps a non-2xx response to its K3 variant. Consumes the body. */
export async function errorFromResponse(res: Response, ctx: ResponseContext): Promise<LingaraError> {
  const text = await res.text().catch(() => "");
  if (res.status === 503 && !isJson(res)) {
    return new MaintenanceError(truncateUtf8(text, MAINTENANCE_BODY_BYTES), ctx.retryAfter);
  }
  const body = parseObject(text);
  return ctx.endpoint === "token" ? oauthError(res.status, body, ctx) : apiError(res.status, body, ctx);
}

function apiError(status: number, body: Record<string, unknown> | undefined, ctx: ResponseContext): ApiError {
  const code = body?.["code"];
  const message = body?.["error"];
  const envelope = typeof code === "string" && typeof message === "string";
  return new ApiError({
    status,
    code: envelope ? code : `http_${status}`,
    message: envelope ? message : `HTTP ${status}`,
    retryAfter: ctx.retryAfter,
    servedVersion: ctx.servedVersion,
  });
}

function oauthError(status: number, body: Record<string, unknown> | undefined, ctx: ResponseContext): OAuthError {
  const error = body?.["error"];
  if (typeof error !== "string") return new OAuthError({ status, error: `http_${status}`, retryAfter: ctx.retryAfter });
  const description = body?.["error_description"];
  return new OAuthError({
    status,
    error,
    description: typeof description === "string" ? description : undefined,
    retryAfter: ctx.retryAfter,
  });
}

/** The media type, parameters ignored, lower-cased. */
export function mediaType(res: Response): string {
  return (res.headers.get("content-type") ?? "").split(";")[0]!.trim().toLowerCase();
}

function isJson(res: Response): boolean {
  const type = mediaType(res);
  return type === "application/json" || type.endsWith("+json");
}

function parseObject(text: string): Record<string, unknown> | undefined {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : undefined;
  } catch {
    return undefined;
  }
}

function truncateUtf8(text: string, maxBytes: number): string {
  const bytes = new TextEncoder().encode(text);
  if (bytes.length <= maxBytes) return text;
  return new TextDecoder().decode(bytes.slice(0, maxBytes)).replace(/�$/, "");
}

// fetch failure → transport kind, and redaction of its cause
const TLS = /cert|ssl|tls|unable_to_verify/i;
const CONNECT = /refused|enotfound|eai_again|dns/i;
const RESET = /reset|epipe|socket|closed|aborted/i;

/**
 * The kind of a failed `fetch()` (`phase: "fetch"`) or body read
 * (`phase: "body"`). Checked tls, then connect, then reset; an unrecognised
 * failure is `connect` before the response and `reset` after it.
 */
export function transportKind(err: unknown, phase: "fetch" | "body"): "tls" | "connect" | "reset" {
  const text = describe(err).join("\n");
  if (TLS.test(text)) return "tls";
  if (CONNECT.test(text)) return "connect";
  if (RESET.test(text)) return "reset";
  return phase === "fetch" ? "connect" : "reset";
}

/** Every code and message on the error and its `cause` chain. */
function describe(err: unknown, depth = 0): string[] {
  if (typeof err !== "object" || err === null || depth > 4) return [];
  const e = err as { code?: unknown; message?: unknown; cause?: unknown };
  const own = [e.code, e.message].filter((v): v is string => typeof v === "string");
  return [...own, ...describe(e.cause, depth + 1)];
}

/**
 * A copy of a runtime error, and of its whole `cause` chain, with every
 * occurrence of each secret replaced by `[REDACTED]` in `message` and
 * `stack`. The runtime's own object is left untouched.
 */
export function redactCause(err: unknown, secrets: readonly (string | undefined)[], depth = 0): unknown {
  const live = secrets.filter((s): s is string => typeof s === "string" && s.length > 0);
  if (typeof err === "string") return scrub(err, live);
  if (typeof err !== "object" || err === null || depth > 4) return err;
  return redactObject(err as ErrorLike, live, depth);
}

interface ErrorLike {
  name?: unknown;
  message?: unknown;
  stack?: unknown;
  code?: unknown;
  cause?: unknown;
}

function redactObject(e: ErrorLike, secrets: readonly string[], depth: number): Error {
  const copy = new Error(scrub(String(e.message ?? ""), secrets));
  copy.name = typeof e.name === "string" ? e.name : "Error";
  if (typeof e.stack === "string") copy.stack = scrub(e.stack, secrets);
  if (typeof e.code === "string") Object.assign(copy, { code: e.code });
  if (e.cause !== undefined) copy.cause = redactCause(e.cause, secrets, depth + 1);
  return copy;
}

function scrub(text: string, secrets: readonly string[]): string {
  return secrets.reduce((acc, s) => acc.split(s).join(REDACTED), text);
}
