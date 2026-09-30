import { describe, expect, it } from "vitest";

import { OAuthError } from "../errors.js";
import { ClientCredentials, formEncode } from "../token.js";
import { fakeFetch, json, recordingSleeper, tokenOk, virtualClock, type Script } from "./fixtures/fakeFetch.js";

const ID = "lgr_cid_unit0000000000000000";
const SECRET = "lgr_cs_unit+secret/000000000000000000000000000000";

function source(scripts: Script[], extra: { auth?: "basic" | "post" } = {}) {
  const clock = virtualClock();
  const { sleeper, sleeps } = recordingSleeper();
  const fake = fakeFetch(...scripts);
  const creds = new ClientCredentials({ clientId: ID, clientSecret: SECRET, clock, sleeper, fetch: fake.fetch, ...extra });
  return { creds, clock, sleeps, calls: fake.calls };
}

/** Resolves a script only when the test says so. */
function gate(): { script: Script; open(res: Response): void } {
  let release!: (res: Response) => void;
  const pending = new Promise<Response>((r) => (release = r));
  return { script: () => pending, open: (res) => release(res) };
}

describe("ClientCredentials", () => {
  /** 29.9.26o AC3: eight concurrent calls, one exchange; a failed flight rejects all eight alike and caches nothing. */
  it("single flight shares one exchange and caches no failure", async () => {
    const g = gate();
    const { creds, calls } = source([g.script, tokenOk("lgr_at_two")]);
    const waiters = Array.from({ length: 8 }, () => creds.token());
    g.open(json(400, { error: "invalid_scope", error_description: "no" }));
    const results = await Promise.allSettled(waiters);
    expect(calls).toHaveLength(1);
    const reasons = results.map((r) => (r.status === "rejected" ? r.reason : undefined));
    expect(reasons[0]).toBeInstanceOf(OAuthError);
    expect(reasons.every((r) => r === reasons[0])).toBe(true);
    expect(creds.exposeToken()).toBeUndefined();
    await expect(creds.token()).resolves.toBe("lgr_at_two");
    expect(calls).toHaveLength(2);
  });

  /** 29.9.26o AC4: reused at 3539 s, replaced at 3541 s after send; a 40 s token is stale at 20 s. */
  it("refreshes at min of sixty seconds and half the lifetime", async () => {
    const long = source([tokenOk("a"), tokenOk("b")]);
    expect(await long.creds.token()).toBe("a");
    long.clock.advance(3539_000);
    expect(await long.creds.token()).toBe("a");
    long.clock.advance(2_000);
    expect(await long.creds.token()).toBe("b");

    const short = source([tokenOk("c", 40), tokenOk("d", 40)]);
    expect(await short.creds.token()).toBe("c");
    short.clock.advance(19_999);
    expect(await short.creds.token()).toBe("c");
    short.clock.advance(1);
    expect(await short.creds.token()).toBe("d");
  });

  /** 29.9.26o AC5: invalidate is compare-and-clear. */
  it("invalidate is compare and clear", async () => {
    const { creds, calls } = source([tokenOk("old"), tokenOk("new")]);
    expect(await creds.token()).toBe("old");
    creds.invalidate("old");
    expect(await creds.token()).toBe("new");
    creds.invalidate("old");
    expect(await creds.token()).toBe("new");
    expect(calls).toHaveLength(2);
  });

  /** 29.9.26o AC6: a cancelled waiter rejects with signal.reason; the flight completes and is cached. */
  it("a cancelled waiter leaves the flight running", async () => {
    const g = gate();
    const { creds, calls } = source([g.script]);
    const ac = new AbortController();
    const cancelled = creds.token({ signal: ac.signal });
    const other = creds.token();
    const reason = new Error("caller gave up");
    ac.abort(reason);
    await expect(cancelled).rejects.toBe(reason);
    expect(calls[0]?.signal?.aborted).toBe(false);
    g.open(json(200, { access_token: "kept", token_type: "bearer", expires_in: 3600 }));
    await expect(other).resolves.toBe("kept");
    await expect(creds.token()).resolves.toBe("kept");
    expect(calls).toHaveLength(1);
  });

  it("sends Basic with form-encoded halves, or the post body", async () => {
    const basic = source([tokenOk("x")]);
    await basic.creds.token();
    const auth = basic.calls[0]!.headers.get("authorization")!;
    expect(atob(auth.replace("Basic ", ""))).toBe(`${ID}:${formEncode(SECRET)}`);
    expect(formEncode("a b!'()*")).toBe("a+b%21%27%28%29*");
    expect(basic.calls[0]!.body).toBe("grant_type=client_credentials");

    const post = source([tokenOk("y")], { auth: "post" });
    await post.creds.token();
    expect(post.calls[0]!.headers.get("authorization")).toBeNull();
    expect(new URLSearchParams(post.calls[0]!.body).get("client_secret")).toBe(SECRET);
  });

  it("retries a 429 on the exchange's own budget", async () => {
    const { creds, sleeps } = source([() => json(429, { error: "rate_limited" }, { "retry-after": "4" }), tokenOk("z")]);
    await expect(creds.token()).resolves.toBe("z");
    expect(sleeps).toEqual([4000]);
  });
});
