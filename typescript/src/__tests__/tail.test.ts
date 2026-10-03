import { describe, expect, it } from "vitest";

import { Lingara } from "../client.js";
import { EventFeed } from "../events/feed.js";
import { backoffSeconds } from "../events/tail.js";
import { pushable, fakeFetch, json, recordingSleeper, sse, tokenOk, type Recorded } from "./fixtures/fakeFetch.js";

const CREDS = { clientId: "lgr_cid_unit", clientSecret: "lgr_cs_unit" };

const ENVELOPE = {
  id: "lgr_evt_unit1",
  type: "lesson_plan.ready",
  created_at: "2026-10-01T09:12:44Z",
  api_version: "2026-09-equipped-boxfish",
  subject: "lgr_sub_unit",
  data: { plan_id: "p1", status: "complete", title: null, source_lang: "en", target_lang: "zh", level: 2 },
};

const eventFrame = (id: string, envelope: unknown = ENVELOPE) => `id: ${id}\nevent: event\ndata: ${JSON.stringify(envelope)}\n\n`;

/** A sleeper that records each delay and then waits until the signal aborts. */
function stuckSleeper(): { sleeper: (ms: number, signal?: AbortSignal) => Promise<void>; sleeps: number[] } {
  const sleeps: number[] = [];
  const sleeper = (ms: number, signal?: AbortSignal) =>
    new Promise<void>((_, reject) => {
      sleeps.push(ms);
      signal?.addEventListener("abort", () => reject(signal.reason), { once: true });
    });
  return { sleeper, sleeps };
}

/** Resolves once `calls` holds `n` requests. */
async function requests(calls: Recorded[], n: number): Promise<void> {
  for (let i = 0; i < 200 && calls.length < n; i++) await new Promise((r) => setTimeout(r, 5));
  expect(calls.length).toBe(n);
}

describe("tailEvents", () => {
  /** 30.9.26aa AC31: cancelling the tail during a reconnect sleep ends it with no further request. */
  it("an abort during a reconnect sleep makes no further request", async () => {
    const { sleeper, sleeps } = stuckSleeper();
    // One event, then EOF: a failed reopen, so the tail sleeps 1 s.
    const fake = fakeFetch(tokenOk("t"), sse([eventFrame("c1")]));
    const ac = new AbortController();
    const tail = new Lingara({ ...CREDS, fetch: fake.fetch, sleeper }).tailEvents({}, { signal: ac.signal });
    expect((await tail.next()).value).toMatchObject({ id: "lgr_evt_unit1", type: "lesson_plan.ready" });
    const pending = tail.next();
    await new Promise((r) => setTimeout(r, 20));
    expect(sleeps).toEqual([1000]);
    const reason = new Error("game closed");
    ac.abort(reason);
    await expect(pending).rejects.toBe(reason);
    await new Promise((r) => setTimeout(r, 20));
    expect(fake.calls.length).toBe(2);
    expect(await tail.next()).toEqual({ done: true, value: undefined });
    expect(tail.cursor).toBe("c1");
  });

  /** 30.9.26aa D7: a `done` is never yielded, yet its id moves `cursor`, and the reopen is at once and carries it. */
  it("a done moves cursor with no event", async () => {
    const rec = recordingSleeper();
    const quiet = pushable();
    const fake = fakeFetch(tokenOk("t"), sse(["id: h1\nevent: done\ndata: {}\n\n"]), () => quiet.response());
    const ac = new AbortController();
    const tail = new Lingara({ ...CREDS, fetch: fake.fetch, sleeper: rec.sleeper }).tailEvents({ start: "oldest" }, { signal: ac.signal });
    const pending = tail.next().catch((e: unknown) => e);
    await requests(fake.calls, 3);
    expect(tail.cursor).toBe("h1");
    expect(rec.sleeps).toEqual([]);
    expect(fake.calls[1]!.headers.get("last-event-id")).toBeNull();
    expect(fake.calls[2]!.headers.get("last-event-id")).toBe("h1");
    // The reopen repeats the first URL: `start`, never a `cursor` query.
    expect(fake.calls[2]!.url).toBe(fake.calls[1]!.url);
    expect(fake.calls[2]!.url).toMatch(/\?start=oldest$/);
    ac.abort(new Error("stop"));
    await pending;
    expect(quiet.cancelled).toBe(true);
  });

  it("a known type whose data does not decode is raised, not reconnected", async () => {
    const rec = recordingSleeper();
    const bad = { ...ENVELOPE, data: { plan_id: 5 } };
    const fake = fakeFetch(tokenOk("t"), sse([eventFrame("c1", bad)]));
    const tail = new Lingara({ ...CREDS, fetch: fake.fetch, sleeper: rec.sleeper }).tailEvents({ cursor: "c0" });
    await expect(tail.next()).rejects.toMatchObject({ kind: "malformed_event" });
    expect(fake.calls[1]!.headers.get("last-event-id")).toBe("c0");
    expect(fake.calls[1]!.url).not.toContain("cursor");
    expect(rec.sleeps).toEqual([]);
  });

  it("backs off 1, 2, 4, 8, 16 then 30 s", () => {
    expect([1, 2, 3, 4, 5, 6, 7, 8].map(backoffSeconds)).toEqual([1, 2, 4, 8, 16, 30, 30, 30]);
  });
});

describe("events", () => {
  it("the feed's cursor moves at a page's last item and on an empty page; start only without a cursor", async () => {
    const page = (items: unknown[], next: string, more: boolean) => () => json(200, { items, next_cursor: next, has_more: more });
    const fake = fakeFetch(tokenOk("t"), page([], "c1", true), page([ENVELOPE, { ...ENVELOPE, id: "lgr_evt_unit2" }], "c2", false));
    const feed = new Lingara({ ...CREDS, fetch: fake.fetch }).events({ start: "latest", types: ["lesson_plan.ready"] });
    expect(feed).toBeInstanceOf(EventFeed);
    const first = await feed.next();
    expect(first.value).toMatchObject({ id: "lgr_evt_unit1", createdAt: "2026-10-01T09:12:44Z", apiVersion: "2026-09-equipped-boxfish" });
    expect(feed.cursor).toBe("c1");
    expect((await feed.next()).value).toMatchObject({ id: "lgr_evt_unit2" });
    expect(feed.cursor).toBe("c2");
    expect((await feed.next()).done).toBe(true);
    expect(fake.calls[1]!.url).toMatch(/\/v1\/events\?start=latest&types=lesson_plan.ready$/);
    expect(fake.calls[2]!.url).toMatch(/\/v1\/events\?cursor=c1&types=lesson_plan.ready$/);
  });
});
