package com.getlingara.client.events;

import java.util.List;

/**
 * Where an events call starts and what it reads (ADR 30.9.26aa D6, D7): {@code listEvents}, {@code
 * events}, {@code streamEvents} and {@code tailEvents} all take one. Start from {@link #of()}.
 *
 * <pre>{@code
 * client.events(EventsRequest.of().cursor(saved).types("lesson_plan.ready"));
 * }</pre>
 *
 * @param cursor an earlier page's {@code next_cursor} or a stream event's {@code id:}, or null
 * @param start {@code latest} or {@code oldest}, where to begin without a cursor, or null for the
 *     server's default ({@code latest})
 * @param types only these event types; empty means every type the token can read
 * @param limit the most events one page returns, 1 to 100, or null; only the feed reads it
 */
public record EventsRequest(String cursor, String start, List<String> types, Integer limit) {
  /** Copies {@code types}; null is empty. */
  public EventsRequest {
    types = types == null ? List.of() : List.copyOf(types);
  }

  /**
   * A request with every field unset: from now, every type.
   *
   * @return the request
   */
  public static EventsRequest of() {
    return new EventsRequest(null, null, List.of(), null);
  }

  /**
   * Continues from a saved cursor.
   *
   * @param cursor the cursor
   * @return a copy with {@code cursor} set
   */
  public EventsRequest cursor(String cursor) {
    return new EventsRequest(cursor, start, types, limit);
  }

  /**
   * Begins at {@code latest} or {@code oldest} when there is no cursor.
   *
   * @param start the starting point
   * @return a copy with {@code start} set
   */
  public EventsRequest start(String start) {
    return new EventsRequest(cursor, start, types, limit);
  }

  /**
   * Reads only these types.
   *
   * @param types the event types
   * @return a copy with {@code types} set
   */
  public EventsRequest types(String... types) {
    return new EventsRequest(cursor, start, List.of(types), limit);
  }

  /**
   * Caps each page of the feed.
   *
   * @param limit 1 to 100
   * @return a copy with {@code limit} set
   */
  public EventsRequest limit(int limit) {
    return new EventsRequest(cursor, start, types, limit);
  }
}
