// The two retries (CONTRACT.md K1 and K4): the `Retry-After` loop around
// one HTTP request, and the one 401 retry around a `/v1` call.

import type { Clock, Sleeper } from "./seams.js";
import type { TokenSource } from "./token.js";

export interface RetryPolicy {
  /** Tries per HTTP request, the first included. `1` turns retries off. */
  maxAttempts: number;
  /** A `Retry-After` above this many seconds is raised, never slept. */
  retryAfterCapSeconds: number;
  clock: Clock;
  sleeper: Sleeper;
}

const DELTA_SECONDS = /^\d+$/;

/**
 * A `Retry-After` value in whole seconds: delta-seconds, or an HTTP-date
 * read against the clock (`max(0, date − now)`, rounded up). Absent or
 * unreadable is `undefined`.
 */
export function parseRetryAfter(value: string | null, clock: Clock): number | undefined {
  if (value === null) return undefined;
  const trimmed = value.trim();
  if (DELTA_SECONDS.test(trimmed)) return Number(trimmed);
  const at = Date.parse(trimmed);
  if (Number.isNaN(at)) return undefined;
  return Math.max(0, Math.ceil((at - clock.now()) / 1000));
}

/**
 * Sends `attempt` until it answers something other than a retryable 429 or
 * 503, or attempts run out; returns the last response. The decision reads
 * the status line and headers only, and a retried response's body is
 * discarded unread.
 */
export async function withRetries(
  policy: RetryPolicy,
  signal: AbortSignal | undefined,
  attempt: () => Promise<Response>,
): Promise<Response> {
  for (let tries = 1; ; tries++) {
    const res = await attempt();
    const wait = retryWait(res, policy, tries);
    if (wait === undefined) return res;
    await res.body?.cancel().catch(() => undefined);
    await policy.sleeper(wait * 1000, signal);
  }
}

function retryWait(res: Response, policy: RetryPolicy, tries: number): number | undefined {
  if (res.status !== 429 && res.status !== 503) return undefined;
  if (tries >= policy.maxAttempts) return undefined;
  const seconds = parseRetryAfter(res.headers.get("retry-after"), policy.clock);
  if (seconds === undefined || seconds > policy.retryAfterCapSeconds) return undefined;
  return seconds;
}

/**
 * K1's one 401 retry: send with a token; on a 401, forget that token (only
 * if it is still the cached one), get another and send once more. The
 * repeated send is a new request, so it gets a fresh retry budget.
 */
export async function withTokenRetry(
  tokens: TokenSource,
  signal: AbortSignal | undefined,
  send: (token: string) => Promise<Response>,
): Promise<Response> {
  const options = signal ? { signal } : {};
  const first = await tokens.token(options);
  const res = await send(first);
  if (res.status !== 401) return res;
  await res.body?.cancel().catch(() => undefined);
  tokens.invalidate(first);
  return send(await tokens.token(options));
}
