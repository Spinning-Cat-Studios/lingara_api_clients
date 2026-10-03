// The client's options and their checks. Every option here is public, and
// the conformance harness builds its clients from these and nothing else.

import { LingaraError } from "./errors.js";
import type { RetryPolicy } from "./retry.js";
import { realSleeper, systemClock, type Clock, type Sleeper } from "./seams.js";
import { ClientCredentials, type TokenSource } from "./token.js";
import type { FetchLike } from "./transport.js";
import type { DeprecationHook } from "./version.js";

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
  /** Consecutive failed reopens before `tailEvents` raises the last (CONTRACT.md K5a). Default 8. */
  tailMaxFailures?: number;
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

export function checkOptions(options: LingaraOptions): void {
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

/** K4's knobs and the two seams, with their defaults. */
export function retryPolicy(options: LingaraOptions): RetryPolicy {
  return {
    maxAttempts: options.maxAttempts ?? 3,
    retryAfterCapSeconds: options.retryAfterCapSeconds ?? 60,
    clock: options.clock ?? systemClock,
    sleeper: options.sleeper ?? realSleeper,
  };
}

export function credentialsFrom(options: LingaraOptions): TokenSource | undefined {
  const { clientId, clientSecret } = options;
  if (clientId === undefined || clientSecret === undefined) return undefined;
  // The source shares the client's credentials, auth, scopes, token URL,
  // retry knobs, seams and fetch; it applies the same defaults.
  return new ClientCredentials({ ...options, clientId, clientSecret });
}
