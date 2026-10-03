// The feed helper over `listEvents` (ADR 30.9.26aa D6; CONTRACT.md, The
// event helpers): pages walked from a cursor, each item parsed into an
// `Event`, and the cursor a caller saves to resume. It never sleeps and
// never polls: it ends where the feed is caught up.

import { parseEvent, type Event } from "../generated/events.js";
import type { EventPage } from "../models.js";

/** Where a feed or a tail starts, and which types it carries. */
export interface EventsParams {
  /** An earlier `cursor` (a page's `next_cursor`, or a stream `id:`). */
  cursor?: string | undefined;
  /** Without a `cursor`: `"latest"` (the server's default) or `"oldest"`. */
  start?: "latest" | "oldest" | undefined;
  /** Only these types. */
  types?: readonly string[] | undefined;
}

/** `listEvents`' query: one page. */
export interface ListEventsParams extends EventsParams {
  /** 1–100; the server's default is 50. */
  limit?: number | undefined;
}

/** The query string for an events route, `types` comma-separated as one value. */
export function eventsQuery(params: ListEventsParams): string {
  const q = new URLSearchParams();
  if (params.cursor !== undefined) q.set("cursor", params.cursor);
  if (params.start !== undefined) q.set("start", params.start);
  if (params.types !== undefined) q.set("types", params.types.join(","));
  if (params.limit !== undefined) q.set("limit", String(params.limit));
  const text = q.toString();
  return text === "" ? "" : `?${text}`;
}

/** Fetches one page. */
export type PageSource = (query: ListEventsParams) => Promise<EventPage>;

/**
 * Every event from `cursor` (or `start`) to where the feed is caught up.
 * `for await` it, then save `cursor` and call `events({ cursor })` again
 * later, or hand it to `tailEvents`.
 */
export class EventFeed implements AsyncIterableIterator<Event> {
  readonly #page: PageSource;
  readonly #types: readonly string[] | undefined;
  #query: ListEventsParams;
  #cursor: string | undefined;
  #items: unknown[] = [];
  #next: string | undefined;
  #more = true;

  constructor(params: EventsParams, page: PageSource) {
    this.#page = page;
    this.#types = params.types;
    this.#cursor = params.cursor;
    // `start` only without a cursor, which is the server's own precedence.
    const from: ListEventsParams = params.cursor !== undefined ? { cursor: params.cursor } : params.start !== undefined ? { start: params.start } : {};
    this.#query = this.#withTypes(from);
  }

  /**
   * Where to resume: once a page's last item has been yielded (or the page
   * was empty), that page's `next_cursor`; before the first page, the
   * caller's `cursor`.
   */
  get cursor(): string | undefined {
    return this.#cursor;
  }

  [Symbol.asyncIterator](): this {
    return this;
  }

  async next(): Promise<IteratorResult<Event>> {
    for (;;) {
      if (this.#items.length > 0) return { done: false, value: this.#take() };
      if (!this.#more) return { done: true, value: undefined };
      await this.#fetch();
    }
  }

  async return(): Promise<IteratorResult<Event>> {
    this.#more = false;
    this.#items = [];
    return { done: true, value: undefined };
  }

  #take(): Event {
    let event: Event;
    try {
      event = parseEvent(this.#items.shift());
    } catch (err) {
      this.#more = false;
      this.#items = [];
      throw err;
    }
    if (this.#items.length === 0) this.#cursor = this.#next;
    return event;
  }

  async #fetch(): Promise<void> {
    let page: EventPage;
    try {
      page = await this.#page(this.#query);
    } catch (err) {
      this.#more = false;
      throw err;
    }
    this.#items = [...page.items];
    this.#next = page.next_cursor;
    this.#more = page.has_more;
    this.#query = this.#withTypes({ cursor: page.next_cursor });
    if (this.#items.length === 0) this.#cursor = page.next_cursor;
  }

  #withTypes(query: ListEventsParams): ListEventsParams {
    return this.#types === undefined ? query : { ...query, types: this.#types };
  }
}
