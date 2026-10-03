// The tail helper over `streamEvents` (ADR 30.9.26aa D7; CONTRACT.md K5a): a
// stream that reopens after every ending from the last `id:` it saw, with
// its own backoff and its own bound, and never ends on its own.

import { ApiError, MaintenanceError, TransportError } from "../errors.js";
import { parseEvent, type Event } from "../generated/events.js";
import type { StreamEventsEvent } from "../models.js";
import type { Sleeper } from "../seams.js";
import type { EventStream } from "../stream.js";

/** The first delay after a failure, and the most any one delay grows to. */
const FIRST_DELAY_S = 1;
const MAX_DELAY_S = 30;

export interface EventTailInit {
  /** One connection: the raw operation, sent with `Last-Event-ID` when given and without K4's retries. */
  open: (lastEventId: string | undefined, signal: AbortSignal) => EventStream<StreamEventsEvent>;
  cursor: string | undefined;
  sleeper: Sleeper;
  retryAfterCapSeconds: number;
  maxFailures: number;
  signal?: AbortSignal | undefined;
}

/** K5a's delay before the `failures`-th consecutive reopen: 1, 2, 4 … 30 s. */
export function backoffSeconds(failures: number): number {
  return Math.min(MAX_DELAY_S, FIRST_DELAY_S * 2 ** (failures - 1));
}

/**
 * What one failed connection means: the seconds to wait before reopening
 * (`undefined` for the backoff delay), or the error itself thrown when it is
 * raised at once rather than reopened.
 */
function failureWait(err: unknown, capSeconds: number): number | undefined {
  if (err instanceof TransportError && err.kind !== "malformed_event") return undefined;
  if (err instanceof ApiError && err.status === 200) return undefined;
  if (!isBusy(err)) throw err;
  if (err.retryAfter !== undefined && err.retryAfter > capSeconds) throw err;
  return err.retryAfter;
}

/** A `429` or `503`: one failed reopen, not a refusal. */
function isBusy(err: unknown): err is ApiError | MaintenanceError {
  return err instanceof MaintenanceError || (err instanceof ApiError && (err.status === 429 || err.status === 503));
}

const REOPEN = Symbol("lingara.reopen");

/**
 * Live events from `cursor`, reconnecting after every ending. `for await` it;
 * `break` or the signal ends it. `cursor` is the `id:` of the last frame
 * that carried one, an event's or a `done`'s.
 */
export class EventTail implements AsyncIterableIterator<Event> {
  readonly #init: EventTailInit;
  readonly #own = new AbortController();
  readonly #signal: AbortSignal;
  #cursor: string | undefined;
  #stream: EventStream<StreamEventsEvent> | undefined;
  #failures = 0;
  #done = false;

  constructor(init: EventTailInit) {
    this.#init = init;
    this.#cursor = init.cursor;
    this.#signal = init.signal ? AbortSignal.any([init.signal, this.#own.signal]) : this.#own.signal;
  }

  /** Where to resume: hand it to `tailEvents` or `events` later. */
  get cursor(): string | undefined {
    return this.#cursor;
  }

  [Symbol.asyncIterator](): this {
    return this;
  }

  async next(): Promise<IteratorResult<Event>> {
    if (this.#done) return { done: true, value: undefined };
    try {
      for (;;) {
        const step = await this.#step();
        if (step !== REOPEN) return { done: false, value: step };
      }
    } catch (err) {
      // A caller's abort arrives here as its own reason, never wrapped.
      await this.close();
      throw err;
    }
  }

  async return(): Promise<IteratorResult<Event>> {
    await this.close();
    return { done: true, value: undefined };
  }

  /** Ends the tail and closes the connection. Idempotent. */
  async close(): Promise<void> {
    if (this.#done) return;
    this.#done = true;
    if (!this.#own.signal.aborted) this.#own.abort();
    await this.#stream?.close();
  }

  /** One frame's worth of progress: an event, or a reopen to make. */
  async #step(): Promise<Event | typeof REOPEN> {
    const stream = (this.#stream ??= this.#init.open(this.#cursor, this.#signal));
    let result: IteratorResult<unknown>;
    try {
      result = await stream.next();
    } catch (err) {
      this.#stream = undefined;
      await this.#failed(err);
      return REOPEN;
    }
    this.#cursor = stream.lastEventId ?? this.#cursor;
    this.#failures = 0;
    if (!result.done) return parseEvent((result.value as { data: unknown }).data);
    // A `done`: its id is already the cursor; reopen at once.
    this.#stream = undefined;
    return REOPEN;
  }

  async #failed(err: unknown): Promise<void> {
    this.#signal.throwIfAborted();
    const wait = failureWait(err, this.#init.retryAfterCapSeconds);
    this.#failures++;
    if (this.#failures >= this.#init.maxFailures) throw err;
    const seconds = wait ?? backoffSeconds(this.#failures);
    await this.#init.sleeper(seconds * 1000, this.#signal);
  }
}
