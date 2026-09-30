// A scripted `fetch`: the unit tests' seam (the `fetch` option). No network.

import type { FetchLike } from "../../transport.js";

export interface Recorded {
  url: string;
  method: string;
  headers: Headers;
  body: string | undefined;
  signal: AbortSignal | undefined;
}

export type Script = (req: Recorded) => Response | Promise<Response>;

/** Answers each request with the next script, in order; records every request. */
export function fakeFetch(...scripts: Script[]): { fetch: FetchLike; calls: Recorded[] } {
  const calls: Recorded[] = [];
  const fetch: FetchLike = async (url, init) => {
    init.signal?.throwIfAborted();
    const req: Recorded = {
      url,
      method: init.method ?? "GET",
      headers: new Headers(init.headers),
      body: typeof init.body === "string" ? init.body : undefined,
      signal: init.signal ?? undefined,
    };
    calls.push(req);
    const script = scripts.shift();
    if (!script) throw new Error(`unexpected request ${req.method} ${url}`);
    return script(req);
  };
  return { fetch, calls };
}

export function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", ...headers } });
}

export function tokenOk(token: string, expiresIn = 3600): Script {
  return () => json(200, { access_token: token, token_type: "Bearer", expires_in: expiresIn, scope: "" });
}

/** A response body the test writes to by hand, and whose cancel it can see. */
export interface Pushable {
  response(headers?: Record<string, string>): Response;
  push(text: string | Uint8Array): void;
  close(): void;
  error(err: unknown): void;
  readonly cancelled: boolean;
}

export function pushable(): Pushable {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel() {
      cancelled = true;
    },
  });
  const encoder = new TextEncoder();
  return {
    response: (headers = {}) =>
      new Response(body, { status: 200, headers: { "content-type": "text/event-stream", ...headers } }),
    push: (chunk) => controller.enqueue(typeof chunk === "string" ? encoder.encode(chunk) : chunk),
    close: () => controller.close(),
    error: (err) => controller.error(err),
    get cancelled() {
      return cancelled;
    },
  };
}

/** A whole SSE body, closed after the last chunk. */
export function sse(chunks: string[], headers: Record<string, string> = {}): Script {
  return () => {
    const p = pushable();
    chunks.forEach((c) => p.push(c));
    p.close();
    return p.response(headers);
  };
}

/** A fixed virtual clock, epoch milliseconds, advanced by hand. */
export function virtualClock(start = 1_790_000_000_000): { now(): number; advance(ms: number): void } {
  let t = start;
  return { now: () => t, advance: (ms) => (t += ms) };
}

/** A sleeper that records each duration and returns at once. */
export function recordingSleeper(): { sleeper: (ms: number, signal?: AbortSignal) => Promise<void>; sleeps: number[] } {
  const sleeps: number[] = [];
  return {
    sleeps,
    sleeper: async (ms, signal) => {
      signal?.throwIfAborted();
      sleeps.push(ms);
    },
  };
}
