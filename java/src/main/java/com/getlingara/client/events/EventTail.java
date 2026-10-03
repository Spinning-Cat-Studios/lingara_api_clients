package com.getlingara.client.events;

import com.getlingara.client.ApiException;
import com.getlingara.client.EventStream;
import com.getlingara.client.LingaraException;
import com.getlingara.client.MaintenanceException;
import com.getlingara.client.Sleeper;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import com.getlingara.client.internal.Retry;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The tail helper behind {@code client.tailEvents(…)}: the event stream, reopened after every
 * ending, so it never ends on its own (CONTRACT.md K5a; ADR 30.9.26aa D7).
 *
 * <p>A {@code done} moves the cursor and reopens at once. An {@code error} event, an end of the
 * body, and every transport failure, the idle timeout included, are failures: the tail sleeps 1 s,
 * doubling up to 30 s, and reopens from the cursor with {@code Last-Event-ID}. The first open
 * counts too. The count resets on the first {@code event} or {@code done} a connection delivers;
 * after {@code maxFailures} in a row (8, about 91 s of sleeps) the last failure is raised. A {@code
 * 429} or {@code 503} is one failure whose {@code Retry-After}, within the cap, replaces that
 * step's delay; every other refusal is raised at once, {@code 410 cursor_expired} included.
 *
 * <p>It is built by {@code LingaraClient.tailEvents}, which hands it out as an {@link EventStream}.
 */
public final class EventTail implements Iterator<Event>, AutoCloseable {
  /** Opens one connection of the event stream, bypassing K4's attempt loop. */
  @FunctionalInterface
  public interface Opener {
    /**
     * Opens one connection.
     *
     * @param lastEventId the cursor to send as {@code Last-Event-ID}, or null for none
     * @return the open stream
     */
    EventStream<Event> open(String lastEventId);
  }

  /**
   * How the tail waits and when it gives up.
   *
   * @param sleeper the client's sleeper
   * @param retryAfterCap the longest {@code Retry-After} worth waiting for
   * @param maxFailures the consecutive failures after which the last is raised
   */
  public record Backoff(Sleeper sleeper, Duration retryAfterCap, int maxFailures) {}

  private static final long MAX_DELAY_SECONDS = 30;

  private final Opener opener;
  private final Backoff backoff;
  private volatile String cursor;
  private volatile EventStream<Event> current;
  private volatile boolean closed;
  private Iterator<Event> events;
  private int failures;
  private Event next;

  /**
   * A tail resuming after {@code cursor}.
   *
   * @param cursor the caller's cursor, sent as the first open's {@code Last-Event-ID}, or null
   * @param opener opens one connection
   * @param backoff the waits and the bound
   */
  public EventTail(String cursor, Opener opener, Backoff backoff) {
    this.cursor = cursor;
    this.opener = opener;
    this.backoff = backoff;
  }

  /**
   * Returns the {@code id:} of the last {@code event} or {@code done} seen, or the caller's cursor
   * before any: a game that saves it resumes with no gap.
   *
   * @return the cursor
   */
  public Optional<String> cursor() {
    return Optional.ofNullable(cursor);
  }

  @Override
  public boolean hasNext() {
    if (next == null && !closed) {
      next = advance();
    }
    return next != null;
  }

  @Override
  public Event next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }
    Event event = next;
    next = null;
    return event;
  }

  /** Ends the tail and closes its connection; safe from any thread. */
  @Override
  public void close() {
    closed = true;
    EventStream<Event> open = current;
    if (open != null) {
      open.close();
    }
  }

  /** Opens and reads until an event, or until the tail is closed. */
  private Event advance() {
    while (!closed) {
      if (current == null && !open()) {
        continue;
      }
      Event event = read();
      if (event != null) {
        return event;
      }
    }
    return null;
  }

  /** One open; false when it failed and its backoff has been slept. */
  private boolean open() {
    try {
      current = opener.open(cursor);
      events = current.iterator();
      return true;
    } catch (ApiException e) {
      if (e.status() != 429 && e.status() != 503) {
        throw e;
      }
      failed(e, e.retryAfter());
    } catch (MaintenanceException e) {
      failed(e, e.retryAfter());
    } catch (TransportException e) {
      failed(e, Optional.empty());
    }
    return false;
  }

  /** The next event of the open connection, or null when it ended and the tail must reopen. */
  private Event read() {
    try {
      if (events.hasNext()) {
        Event event = events.next();
        moved();
        return event;
      }
      // A done: its id is the horizon, and the reopen is immediate and not a failure.
      moved();
      drop();
    } catch (ApiException e) {
      failed(e, Optional.empty());
    } catch (TransportException e) {
      if (e.kind() == TransportKind.MALFORMED_EVENT) {
        // A reopen from the same cursor would meet the same frame.
        drop();
        throw e;
      }
      failed(e, Optional.empty());
    }
    return null;
  }

  private void moved() {
    failures = 0;
    cursor = current.cursor().orElse(cursor);
  }

  /**
   * Counts one failure, raising it when the bound is spent or its {@code Retry-After} is above the
   * cap, and otherwise sleeps that step's delay.
   */
  private void failed(LingaraException failure, Optional<Duration> retryAfter) {
    drop();
    if (closed) {
      return;
    }
    if (retryAfter.isPresent() && retryAfter.get().compareTo(backoff.retryAfterCap()) > 0) {
      throw failure;
    }
    failures++;
    if (failures >= backoff.maxFailures()) {
      throw failure;
    }
    long seconds = Math.min(MAX_DELAY_SECONDS, 1L << Math.min(failures - 1, 5));
    Retry.sleep(backoff.sleeper(), retryAfter.orElse(Duration.ofSeconds(seconds)));
  }

  private void drop() {
    EventStream<Event> open = current;
    current = null;
    events = null;
    if (open != null) {
      open.close();
    }
  }
}
