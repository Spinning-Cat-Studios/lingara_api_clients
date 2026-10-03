package com.getlingara.client.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The feed helper, {@code client.events(…)}: every event from a cursor up to now, page by page
 * (CONTRACT.md, The event helpers; ADR 30.9.26aa D6).
 *
 * <p>Each item is parsed into an {@link Event}; a type this library does not know is an {@link
 * UnknownEvent}. Iteration ends on the page that says {@code has_more: false}. It never sleeps and
 * never polls: save {@link #cursor()} and call {@code events} again later, or move to {@code
 * tailEvents}. A cursor older than the 30-day window is {@code ApiException} {@code
 * cursor_expired}: start again without one, or with {@code start("oldest")}.
 *
 * <p>Iterate it on one thread. It is built by {@code LingaraClient.events}.
 */
public final class EventFeed implements Iterator<Event> {
  /** Fetches one page, which is {@code listEvents} read as JSON. */
  @FunctionalInterface
  public interface Pages {
    /**
     * Fetches one page.
     *
     * @param page the page's cursor, or its start when it has none, and the types
     * @return the {@code EventPage} as JSON
     */
    JsonNode fetch(EventsRequest page);
  }

  private final EventsRequest request;
  private final Pages pages;
  private final ArrayDeque<JsonNode> items = new ArrayDeque<>();
  private String cursor;
  private String pageCursor;
  private boolean more = true;

  /**
   * A feed from {@code request}'s cursor, or from its start when it has none.
   *
   * @param request where to begin and what to read
   * @param pages the page source
   */
  public EventFeed(EventsRequest request, Pages pages) {
    this.request = request;
    this.pages = pages;
    this.cursor = request.cursor();
  }

  /**
   * Returns where to continue: after a page's last event is returned, that page's {@code
   * next_cursor}. A page with no events still advances it.
   *
   * @return the cursor, empty only before the first page when no cursor was given
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  @Override
  public boolean hasNext() {
    while (items.isEmpty() && more) {
      fetch();
    }
    return !items.isEmpty();
  }

  /**
   * Returns the next event.
   *
   * @throws TransportException {@code malformed_event} for a known type whose data does not decode
   */
  @Override
  public Event next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    Event event;
    try {
      event = Event.parse(items.poll());
    } catch (IllegalArgumentException e) {
      throw new TransportException(TransportKind.MALFORMED_EVENT, e);
    }
    if (items.isEmpty()) {
      cursor = pageCursor;
    }
    return event;
  }

  /** One page: {@code start} only while there is no cursor, the server's own precedence. */
  private void fetch() {
    EventsRequest page =
        cursor == null
            ? new EventsRequest(null, request.start(), request.types(), request.limit())
            : new EventsRequest(cursor, null, request.types(), request.limit());
    JsonNode body = pages.fetch(page);
    JsonNode list = body.path("items");
    JsonNode next = body.path("next_cursor");
    if (!list.isArray() || !next.isTextual() || !body.path("has_more").isBoolean()) {
      throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
    }
    list.forEach(items::add);
    pageCursor = next.asText();
    more = body.path("has_more").asBoolean();
    if (items.isEmpty()) {
      cursor = pageCursor;
    }
  }
}
