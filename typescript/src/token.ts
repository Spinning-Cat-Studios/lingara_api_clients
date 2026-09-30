// K1: the token source (CONTRACT.md K1). `ClientCredentials` caches one
// token, refreshes it `min(60 s, expires_in / 2)` before it expires, shares
// one exchange between concurrent callers, and clears only the token a 401
// was answered with.

import { INSPECT, REDACTED, TransportError, errorFromResponse } from "./errors.js";
import { parseRetryAfter, withRetries, type RetryPolicy } from "./retry.js";
import { raceAbort, realSleeper, systemClock, type Clock, type Sleeper } from "./seams.js";
import { fetchOnce, transportFailure, type FetchLike } from "./transport.js";
import { userAgent } from "./userAgent.js";

/** Where the client gets its access tokens. A caller may supply their own. */
export interface TokenSource {
  /** An access token. `signal` abandons this caller's wait only. */
  token(options?: { signal?: AbortSignal }): Promise<string>;
  /** Forgets `token` only if it is still the cached one. */
  invalidate(token: string): void;
}

export interface ClientCredentialsOptions {
  clientId: string;
  clientSecret: string;
  /** `"basic"` (the default) is `client_secret_basic`; `"post"` is `client_secret_post`. */
  auth?: "basic" | "post" | undefined;
  scopes?: readonly string[] | undefined;
  tokenUrl?: string | undefined;
  maxAttempts?: number | undefined;
  retryAfterCapSeconds?: number | undefined;
  tokenRequestTimeoutMs?: number | undefined;
  userAgentSuffix?: string | undefined;
  /** A testing seam. */
  clock?: Clock | undefined;
  /** A testing seam. */
  sleeper?: Sleeper | undefined;
  fetch?: FetchLike | undefined;
}

export const DEFAULT_TOKEN_URL = "https://api.getlingara.com/oauth/token";

interface Cached {
  token: string;
  staleAt: number;
}

/** The OAuth 2.0 client-credentials grant against `/oauth/token`. */
export class ClientCredentials implements TokenSource {
  readonly clientId: string;
  #secret: string;
  #cached: Cached | undefined;
  #flight: Promise<string> | undefined;
  readonly #options: {
    auth: "basic" | "post";
    scopes: readonly string[] | undefined;
    tokenUrl: string;
    maxAttempts: number;
    retryAfterCapSeconds: number;
    tokenRequestTimeoutMs: number;
    userAgent: string;
    clock: Clock;
    sleeper: Sleeper;
    fetch: FetchLike;
  };

  constructor(options: ClientCredentialsOptions) {
    this.clientId = options.clientId;
    this.#secret = options.clientSecret;
    this.#options = {
      auth: options.auth ?? "basic",
      scopes: options.scopes,
      tokenUrl: options.tokenUrl ?? DEFAULT_TOKEN_URL,
      maxAttempts: options.maxAttempts ?? 3,
      retryAfterCapSeconds: options.retryAfterCapSeconds ?? 60,
      tokenRequestTimeoutMs: options.tokenRequestTimeoutMs ?? 30_000,
      userAgent: userAgent(options.userAgentSuffix),
      clock: options.clock ?? systemClock,
      sleeper: options.sleeper ?? realSleeper,
      fetch: options.fetch ?? ((input, init) => globalThis.fetch(input, init)),
    };
  }

  async token(options: { signal?: AbortSignal } = {}): Promise<string> {
    options.signal?.throwIfAborted();
    const cached = this.#cached;
    if (cached && this.#options.clock.now() < cached.staleAt) return cached.token;
    if (!this.#flight) {
      // The exchange carries no caller's signal: it is shared, so one
      // caller's cancel abandons only that caller's wait.
      const flight = this.#exchange();
      this.#flight = flight;
      flight.then(
        () => this.#settle(flight),
        () => this.#settle(flight),
      );
    }
    return raceAbort(this.#flight, options.signal);
  }

  invalidate(token: string): void {
    if (this.#cached?.token === token) this.#cached = undefined;
  }

  /** The cached access token, raw. The one accessor that does not redact. */
  exposeToken(): string | undefined {
    return this.#cached?.token;
  }

  toJSON(): Record<string, unknown> {
    return { clientId: this.clientId, clientSecret: REDACTED, token: this.#cached ? REDACTED : undefined };
  }

  [INSPECT](): string {
    return `ClientCredentials ${JSON.stringify(this.toJSON())}`;
  }

  toString(): string {
    return this[INSPECT]();
  }

  #settle(flight: Promise<string>): void {
    if (this.#flight === flight) this.#flight = undefined;
  }

  async #exchange(): Promise<string> {
    const o = this.#options;
    const policy: RetryPolicy = { maxAttempts: o.maxAttempts, retryAfterCapSeconds: o.retryAfterCapSeconds, clock: o.clock, sleeper: o.sleeper };
    let sentAt = o.clock.now();
    const res = await withRetries(policy, undefined, () => {
      sentAt = o.clock.now();
      return this.#post();
    });
    if (!res.ok) {
      const retryAfter = parseRetryAfter(res.headers.get("retry-after"), o.clock);
      throw await errorFromResponse(res, { endpoint: "token", retryAfter });
    }
    const grant = await this.#readGrant(res);
    const skew = Math.min(60, grant.expiresIn / 2);
    this.#cached = { token: grant.token, staleAt: sentAt + (grant.expiresIn - skew) * 1000 };
    return grant.token;
  }

  async #post(): Promise<Response> {
    const o = this.#options;
    const headers: Record<string, string> = {
      "content-type": "application/x-www-form-urlencoded",
      accept: "application/json",
      "user-agent": o.userAgent,
    };
    const body = new URLSearchParams({ grant_type: "client_credentials" });
    if (o.scopes && o.scopes.length > 0) body.set("scope", o.scopes.join(" "));
    if (o.auth === "post") {
      body.set("client_id", this.clientId);
      body.set("client_secret", this.#secret);
    } else {
      headers["authorization"] = `Basic ${btoa(`${formEncode(this.clientId)}:${formEncode(this.#secret)}`)}`;
    }
    const signal = AbortSignal.timeout(o.tokenRequestTimeoutMs);
    try {
      return await fetchOnce({ fetch: o.fetch, url: o.tokenUrl, init: { method: "POST", headers, body: body.toString(), signal }, secrets: [this.#secret] });
    } catch (err) {
      throw signal.aborted ? new TransportError("timeout") : err;
    }
  }

  async #readGrant(res: Response): Promise<{ token: string; expiresIn: number }> {
    let body: unknown;
    try {
      body = await res.json();
    } catch (err) {
      throw this.#readFailure(err);
    }
    const grant = (body ?? {}) as { access_token?: unknown; expires_in?: unknown; token_type?: unknown };
    const { access_token: token, expires_in: expiresIn, token_type: type } = grant;
    const bearer = typeof type === "string" && type.toLowerCase() === "bearer";
    if (typeof token !== "string" || typeof expiresIn !== "number" || !bearer) {
      throw new TransportError("malformed_response");
    }
    return { token, expiresIn };
  }

  #readFailure(err: unknown): unknown {
    if (err instanceof SyntaxError) return new TransportError("malformed_response");
    if ((err as { name?: unknown } | null)?.name === "TimeoutError") return new TransportError("timeout");
    return transportFailure(err, undefined, "body", [this.#secret]);
  }
}

/** The `application/x-www-form-urlencoded` serializer, RFC 6749 §2.3.1. */
export function formEncode(value: string): string {
  return new URLSearchParams([["", value]]).toString().slice(1);
}
