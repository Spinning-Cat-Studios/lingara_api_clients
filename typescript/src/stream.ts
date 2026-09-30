// K5: one stream as an async iterator (CONTRACT.md K5 and Cancellation).
//
// The request starts on the first `next()` (or the first read of
// `servedVersion`). Frames come from the pure parser in sse.ts; this file
// owns the bytes, the idle timeout, the terminal events and closing the
// connection on every exit path.

import { ApiError, LingaraError, TransportError, transportKind, redactCause } from "./errors.js";
import { STREAMS, type StreamOperation, type StreamOutcome } from "./generated/streams.js";
import { raceAbort } from "./seams.js";
import { SseParser, type Frame } from "./sse.js";

/**
 * What `event` does to `operation`'s iteration, from the generated `ends`
 * rows (ADR 29.9.26ai D2); `undefined` when the event does not end it.
 */
export function outcomeOf(operation: StreamOperation, event: string): StreamOutcome | undefined {
  const ends: Readonly<Record<string, StreamOutcome>> = STREAMS[operation].ends;
  return Object.hasOwn(ends, event) ? ends[event] : undefined;
}

/** What a stream yields: every event but `done` and `error`. */
export type Yielded<E> = Exclude<E, { event: "done" } | { event: "error" }>;

/** An opened stream: a 200 `text/event-stream` response and its echo. */
export interface OpenedStream {
  response: Response;
  servedVersion: string | undefined;
}

export interface EventStreamInit {
  operation: StreamOperation;
  /** Sends the request (auth, retries, error mapping) under `signal`. */
  open: (signal: AbortSignal) => Promise<OpenedStream>;
  signal?: AbortSignal | undefined;
  idleTimeoutMs: number;
}

// Private abort reasons, so the iterator can tell its own timeout and its
// own close from a caller's abort.
const IDLE = Symbol("lingara.idle");
const CLOSED = Symbol("lingara.closed");

/** A stream of events. `for await` it; `break` or `close()` ends it. */
export class EventStream<E extends { event: string }> implements AsyncIterableIterator<Yielded<E>> {
  readonly #init: EventStreamInit;
  readonly #own = new AbortController();
  readonly #signal: AbortSignal;
  readonly #parser = new SseParser();
  readonly #decoder = new TextDecoder("utf-8");
  readonly #frames: Frame[] = [];
  #started: Promise<OpenedStream> | undefined;
  #reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
  #servedVersion: string | undefined;
  #eof = false;
  #done = false;

  constructor(init: EventStreamInit) {
    this.#init = init;
    this.#signal = init.signal ? AbortSignal.any([init.signal, this.#own.signal]) : this.#own.signal;
  }

  /**
   * The `Lingara-Version` echo. Reading it starts the request if iteration
   * has not. Never rejects: `undefined` when the request fails or the stream
   * closes first.
   */
  get servedVersion(): Promise<string | undefined> {
    if (this.#done && !this.#started) return Promise.resolve(undefined);
    return this.#start().then(
      (opened) => opened.servedVersion,
      () => undefined,
    );
  }

  [Symbol.asyncIterator](): this {
    return this;
  }

  async next(): Promise<IteratorResult<Yielded<E>>> {
    if (this.#done) return { done: true, value: undefined };
    try {
      await this.#start();
      for (;;) {
        const frame = this.#frames.shift();
        if (frame) {
          const result = this.#handle(frame);
          if (result) return result;
          continue;
        }
        if (this.#eof) throw new TransportError("stream_ended_early");
        await this.#read();
      }
    } catch (err) {
      return this.#fail(err);
    }
  }

  async return(): Promise<IteratorResult<Yielded<E>>> {
    await this.close();
    return { done: true, value: undefined };
  }

  /** Ends the stream and closes the connection. Idempotent. */
  async close(): Promise<void> {
    this.#finish(CLOSED);
  }

  #start(): Promise<OpenedStream> {
    this.#started ??= this.#init.open(this.#signal).then((opened) => {
      this.#servedVersion = opened.servedVersion;
      const body = opened.response.body;
      if (!body) throw new TransportError("stream_ended_early");
      this.#reader = body.getReader();
      if (this.#done) this.#reader.cancel().catch(() => undefined);
      return opened;
    });
    return this.#started;
  }

  async #read(): Promise<void> {
    const reader = this.#reader!;
    // The idle timer runs only while a read is pending, and every chunk
    // (a keepalive comment included) re-arms it.
    const timer = setTimeout(() => this.#own.abort(IDLE), this.#init.idleTimeoutMs);
    try {
      const chunk = await raceAbort(reader.read(), this.#signal);
      if (chunk.done) {
        this.#frames.push(...this.#parser.push(this.#decoder.decode()), ...this.#parser.end());
        this.#eof = true;
      } else {
        this.#frames.push(...this.#parser.push(this.#decoder.decode(chunk.value, { stream: true })));
      }
    } finally {
      clearTimeout(timer);
    }
  }

  #handle(frame: Frame): IteratorResult<Yielded<E>> | undefined {
    const events: readonly string[] = STREAMS[this.#init.operation].events;
    if (!events.includes(frame.event)) return undefined;
    const data = decode(frame.data);
    const outcome = outcomeOf(this.#init.operation, frame.event);
    if (outcome === "raise") throw this.#streamError(data);
    // Typed as the operation's union: the name was checked against the
    // spec's event names; the payload is the conformance suite's promise.
    const value = { event: frame.event, data } as unknown as Yielded<E>;
    if (outcome === undefined) return { done: false, value };
    this.#finish(CLOSED);
    if (outcome === "end") return { done: true, value: undefined };
    return { done: false, value };
  }

  #streamError(data: unknown): ApiError {
    const d = (data ?? {}) as { code?: unknown; message?: unknown; plan_id?: unknown };
    return new ApiError({
      status: 200,
      code: typeof d.code === "string" ? d.code : "stream_error",
      message: typeof d.message === "string" ? d.message : "the stream reported an error",
      planId: typeof d.plan_id === "string" ? d.plan_id : undefined,
      servedVersion: this.#servedVersion,
    });
  }

  #fail(err: unknown): IteratorResult<Yielded<E>> {
    const reason: unknown = this.#signal.aborted ? this.#signal.reason : undefined;
    this.#finish(CLOSED);
    if (reason === CLOSED) return { done: true, value: undefined };
    if (reason === IDLE) throw new TransportError("timeout");
    if (reason !== undefined) throw reason;
    if (err instanceof LingaraError) throw err;
    throw new TransportError(transportKind(err, "body"), redactCause(err, []));
  }

  /** Marks the stream done and closes the connection; clears nothing twice. */
  #finish(reason: symbol): void {
    if (this.#done) return;
    this.#done = true;
    if (!this.#own.signal.aborted) this.#own.abort(reason);
    this.#reader?.cancel().catch(() => undefined);
  }
}

function decode(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    throw new TransportError("malformed_event");
  }
}
