import { readFileSync } from "node:fs";

import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, TransportError } from "../errors.js";
import { STREAMS, type StreamOperation } from "../generated/streams.js";
import { EventStream, outcomeOf, type OpenedStream } from "../stream.js";
import { pushable, type Pushable } from "./fixtures/fakeFetch.js";

type AnyEvent = { event: string; data: unknown };

type ViewStream = { operationId: string; union: string; endsOn: string[]; error: string };
type View = {
  "x-lingara-streams": ViewStream[];
  components: { schemas: Record<string, { discriminator?: { mapping: Record<string, string> }; properties?: { data: { $ref: string } } }> };
};

/** CONTRACT.md K5's rule, read straight off the view (ADR 29.9.26ai D2). */
function ruleOutcome(view: View, s: ViewStream, event: string): string {
  if (event === s.error) return "raise";
  const schemas = view.components.schemas;
  const branch = schemas[schemas[s.union]!.discriminator!.mapping[event]!.split("/").pop()!]!;
  return branch.properties!.data.$ref === "#/components/schemas/Done" ? "end" : "yield";
}

function streamOver(p: Pushable, options: { operation?: StreamOperation; idleTimeoutMs?: number; signal?: AbortSignal; servedVersion?: string } = {}) {
  let opens = 0;
  const open = async (): Promise<OpenedStream> => {
    opens++;
    return { response: p.response(), servedVersion: options.servedVersion };
  };
  const stream = new EventStream<AnyEvent>({
    operation: options.operation ?? "generateVocabulary",
    open,
    signal: options.signal,
    idleTimeoutMs: options.idleTimeoutMs ?? 120_000,
  });
  return { stream, opens: () => opens };
}

const frame = (event: string, data: unknown) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;

async function drain(stream: EventStream<AnyEvent>): Promise<AnyEvent[]> {
  const out: AnyEvent[] = [];
  for await (const ev of stream) out.push(ev);
  return out;
}

afterEach(() => {
  vi.useRealTimers();
});

describe("EventStream", () => {
  /** 29.9.26o AC9: an abort rejects with signal.reason and closes the body; break closes it silently. */
  it("abort and break both close the connection", async () => {
    const aborted = pushable();
    const ac = new AbortController();
    const { stream } = streamOver(aborted, { signal: ac.signal });
    aborted.push(frame("started", { meta: {} }));
    expect((await stream.next()).value).toEqual({ event: "started", data: { meta: {} } });
    const pending = stream.next();
    const reason = new Error("caller cancelled");
    ac.abort(reason);
    await expect(pending).rejects.toBe(reason);
    expect(aborted.cancelled).toBe(true);
    expect(await stream.next()).toEqual({ done: true, value: undefined });

    const broken = pushable();
    const second = streamOver(broken).stream;
    broken.push(frame("started", { meta: {} }) + frame("item", { word: "a" }));
    for await (const ev of second) {
      expect(ev.event).toBe("started");
      break;
    }
    expect(broken.cancelled).toBe(true);
  });

  /** 29.9.26o AC10: the idle timeout is an option, runs only while a read is pending, and a keepalive resets it. */
  it("idle timeout is an option and a keepalive resets it", async () => {
    vi.useFakeTimers();
    // Silent for 50 ms while a next() is pending: timeout.
    const silent = pushable();
    const a = streamOver(silent, { idleTimeoutMs: 50 }).stream;
    const failed = a.next().catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(50);
    const err = await failed;
    expect(err).toBeInstanceOf(TransportError);
    expect((err as TransportError).kind).toBe("timeout");
    expect(silent.cancelled).toBe(true);

    // A keepalive every 30 ms keeps it open; holding an event for 100 ms is not silence.
    const lively = pushable();
    const b = streamOver(lively, { idleTimeoutMs: 50 }).stream;
    lively.push(frame("started", { meta: {} }));
    expect((await b.next()).done).toBe(false);
    await vi.advanceTimersByTimeAsync(100);
    const next = b.next();
    for (let i = 0; i < 3; i++) {
      await vi.advanceTimersByTimeAsync(30);
      lively.push(": keepalive\n\n");
    }
    lively.push(frame("item", { word: "x" }));
    expect((await next).value).toEqual({ event: "item", data: { word: "x" } });
    lively.push(frame("done", {}));
    expect((await b.next()).done).toBe(true);
    expect(vi.getTimerCount()).toBe(0);

    // A caller's abort is still the caller's reason, not a timeout.
    const ac = new AbortController();
    const c = streamOver(pushable(), { idleTimeoutMs: 50, signal: ac.signal }).stream;
    const cancelled = c.next().catch((e: unknown) => e);
    await vi.advanceTimersByTimeAsync(10);
    const reason = new Error("stop");
    ac.abort(reason);
    expect(await cancelled).toBe(reason);
    expect(vi.getTimerCount()).toBe(0);
  });

  /**
   * 29.9.26o AC11, 29.9.26ai AC8: stream.ts holds no terminal table; the
   * generated rows name exactly each view entry's endsOn with D2's outcome;
   * and each operation ends on its own terminal.
   */
  it("each operation ends on its own terminal", async () => {
    const source = readFileSync(new URL("../stream.ts", import.meta.url), "utf8");
    expect(source).not.toMatch(/TERMINALS|"pending"|"result"/);
    const view = JSON.parse(readFileSync(new URL("../../../spec/generator/openapi.3.1.json", import.meta.url), "utf8")) as View;
    expect(view["x-lingara-streams"].map((s) => s.operationId).sort()).toEqual(Object.keys(STREAMS).sort());
    for (const s of view["x-lingara-streams"]) {
      const op = s.operationId as StreamOperation;
      expect(Object.keys(STREAMS[op].ends), op).toEqual(s.endsOn);
      for (const e of s.endsOn) expect(outcomeOf(op, e), `${op} → ${e}`).toBe(ruleOutcome(view, s, e));
    }
    const cases: [StreamOperation, string, boolean][] = [
      ["generateVocabulary", "done", false],
      ["sendTutorMessage", "done", false],
      ["sendDialogueTurn", "done", false],
      ["createLessonPlan", "result", true],
      ["streamLessonPlan", "result", true],
      ["streamLessonPlan", "pending", true],
    ];
    for (const [operation, terminal, yielded] of cases) {
      const p = pushable();
      const { stream } = streamOver(p, { operation });
      const first = STREAMS[operation].events[0];
      p.push(frame(first, { n: 1 }) + frame(terminal, { n: 2 }) + frame(first, { n: 3 }));
      const seen = await drain(stream);
      const expected = yielded ? [first, terminal] : [first];
      expect(seen.map((e) => e.event), `${operation} → ${terminal}`).toEqual(expected);
      expect(p.cancelled).toBe(true);
    }
  });

  /** 29.9.26o AC23: an error event raises ApiError with status 200, code, message, planId and servedVersion. */
  it("an error event raises ApiError with plan id", async () => {
    const p = pushable();
    const { stream } = streamOver(p, { operation: "createLessonPlan", servedVersion: "2026-09-knowing-tenpounder" });
    p.push(frame("started", { plan_id: "p1" }));
    p.push(frame("error", { code: "generation_failed", message: "The plan could not be generated.", plan_id: "p1" }));
    const seen: AnyEvent[] = [];
    const err = await (async () => {
      for await (const ev of stream) seen.push(ev);
    })().catch((e: unknown) => e);
    expect(seen.map((e) => e.event)).toEqual(["started"]);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 200, code: "generation_failed", message: "The plan could not be generated.", planId: "p1", servedVersion: "2026-09-knowing-tenpounder" });
  });

  /** 29.9.26o AC24: servedVersion starts the request, settles with the echo, and never rejects. */
  it("served version settles and never rejects", async () => {
    const p = pushable();
    const { stream, opens } = streamOver(p, { servedVersion: "2026-09-knowing-tenpounder" });
    expect(await stream.servedVersion).toBe("2026-09-knowing-tenpounder");
    expect(opens()).toBe(1);
    p.push(frame("started", { meta: {} }) + frame("done", {}));
    expect((await drain(stream)).length).toBe(1);
    expect(opens()).toBe(1);

    const failure = new ApiError({ status: 403, code: "insufficient_scope", message: "no" });
    const failing = new EventStream<AnyEvent>({ operation: "generateVocabulary", open: () => Promise.reject(failure), idleTimeoutMs: 1000 });
    await expect(failing.servedVersion).resolves.toBeUndefined();
    await expect(failing.next()).rejects.toBe(failure);

    const closed = streamOver(pushable()).stream;
    await closed.close();
    await expect(closed.servedVersion).resolves.toBeUndefined();
  });

  it("EOF before a terminal, and malformed data, are transport errors; unknown events are skipped", async () => {
    const early = pushable();
    early.push(frame("started", { meta: {} }));
    early.close();
    await expect(drain(streamOver(early).stream)).rejects.toMatchObject({ kind: "stream_ended_early" });

    const bad = pushable();
    bad.push("event: item\ndata: {nope\n\n");
    await expect(drain(streamOver(bad).stream)).rejects.toMatchObject({ kind: "malformed_event" });

    const unknown = pushable();
    unknown.push(frame("sparkle", { x: 1 }) + "event: sparkle\ndata: not json\n\n" + frame("done", {}));
    expect(await drain(streamOver(unknown).stream)).toEqual([]);
  });
});
