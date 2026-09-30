import { describe, expect, it } from "vitest";

import { parseRetryAfter, withRetries, withTokenRetry, type RetryPolicy } from "../retry.js";
import type { TokenSource } from "../token.js";
import { json, recordingSleeper, virtualClock } from "./fixtures/fakeFetch.js";

function policy(overrides: Partial<RetryPolicy> = {}) {
  const { sleeper, sleeps } = recordingSleeper();
  const p: RetryPolicy = { maxAttempts: 3, retryAfterCapSeconds: 60, clock: virtualClock(), sleeper, ...overrides };
  return { policy: p, sleeps };
}

function answers(...responses: Response[]): { attempt: () => Promise<Response>; count: () => number } {
  let n = 0;
  return {
    attempt: async () => {
      const res = responses[n++];
      if (!res) throw new Error("no more responses");
      return res;
    },
    count: () => n,
  };
}

const tooMany = (retryAfter?: string) => json(429, { code: "rate_limited", error: "Slow down." }, retryAfter ? { "retry-after": retryAfter } : {});

describe("retries", () => {
  /** 29.9.26o AC7: over the cap and missing raise at once; an HTTP-date reads the clock; three 429s raise after two sleeps. */
  it("retry after cap missing header date and exhaustion", async () => {
    const over = policy();
    const a = answers(tooMany("61"));
    expect((await withRetries(over.policy, undefined, a.attempt)).status).toBe(429);
    expect(over.sleeps).toEqual([]);

    const missing = policy();
    const b = answers(tooMany());
    expect((await withRetries(missing.policy, undefined, b.attempt)).status).toBe(429);
    expect(missing.sleeps).toEqual([]);

    // The virtual clock starts at 1790000000 s, 14:13:20 GMT on 21 Sep 2026.
    const dated = policy();
    const c = answers(tooMany("Mon, 21 Sep 2026 14:13:23 GMT"), json(200, {}));
    expect((await withRetries(dated.policy, undefined, c.attempt)).status).toBe(200);
    expect(dated.sleeps).toEqual([3000]);

    const exhausted = policy();
    const d = answers(tooMany("2"), tooMany("2"), tooMany("2"));
    expect((await withRetries(exhausted.policy, undefined, d.attempt)).status).toBe(429);
    expect(exhausted.sleeps).toEqual([2000, 2000]);
    expect(d.count()).toBe(3);
  });

  it("maxAttempts 1 turns retries off, and a cancelled sleep ends the call", async () => {
    const off = policy({ maxAttempts: 1 });
    expect((await withRetries(off.policy, undefined, answers(tooMany("1")).attempt)).status).toBe(429);
    expect(off.sleeps).toEqual([]);

    const ac = new AbortController();
    ac.abort(new Error("stop"));
    const on = policy();
    await expect(withRetries(on.policy, ac.signal, answers(tooMany("1")).attempt)).rejects.toThrow("stop");
  });

  it("reads delta-seconds and HTTP-dates, and nothing else", () => {
    const clock = virtualClock();
    expect(parseRetryAfter("7", clock)).toBe(7);
    expect(parseRetryAfter(null, clock)).toBeUndefined();
    expect(parseRetryAfter("soon", clock)).toBeUndefined();
    expect(parseRetryAfter("Mon, 21 Sep 2026 14:13:00 GMT", clock)).toBe(0);
  });

  it("the one 401 retry invalidates only the rejected token", async () => {
    const tokens = ["t1", "t2"];
    const invalidated: string[] = [];
    const source: TokenSource = { token: async () => tokens.shift()!, invalidate: (t) => void invalidated.push(t) };
    const sent: string[] = [];
    const res = await withTokenRetry(source, undefined, async (token) => {
      sent.push(token);
      return token === "t1" ? json(401, { code: "unauthorized", error: "no" }) : json(200, {});
    });
    expect(res.status).toBe(200);
    expect(sent).toEqual(["t1", "t2"]);
    expect(invalidated).toEqual(["t1"]);
  });
});
