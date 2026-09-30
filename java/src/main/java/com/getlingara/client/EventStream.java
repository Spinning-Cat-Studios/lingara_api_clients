package com.getlingara.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.internal.ErrorMapper;
import com.getlingara.client.internal.Retry;
import com.getlingara.client.internal.SharedExecutor;
import com.getlingara.client.internal.SseDecoder;
import com.getlingara.client.internal.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * One open stream of events (CONTRACT.md K5; ADR 29.9.26r D4, D7, D8). Iterate it once, inside
 * {@code try}-with-resources.
 *
 * <p>The request was sent when the operation returned, so iterating never re-sends it: {@link
 * #iterator()} may be called once, as with {@code java.nio.file.DirectoryStream}. An {@code error}
 * event is raised by {@code hasNext()} as an {@link ApiException} with status 200, never yielded. A
 * {@code Done}-bodied ending event ends iteration unyielded; any other ending event is yielded, and
 * then iteration ends. After a terminal event the body is closed and no later byte is read.
 *
 * <p>Cancellation is {@link #close()} or the reading thread's interrupt. A {@code close()} from
 * another thread, or an interrupt, makes a blocked {@code hasNext()} throw the JDK's {@link
 * java.util.concurrent.CancellationException}; a {@code close()} on the iterating thread, such as a
 * {@code break} out of {@code try}-with-resources, ends iteration silently.
 *
 * @param <E> the operation's event union
 */
public final class EventStream<E> implements Iterable<E>, AutoCloseable {
  private static final int BUFFER_BYTES = 32 * 1024;

  /** Decodes one event of a stream, or returns null for a name its union has no member for. */
  @FunctionalInterface
  interface Decoder<E> {
    E decode(String event, JsonNode data, ObjectMapper mapper) throws IOException;
  }

  private final Streams.Route route;
  private final Decoder<E> decoder;
  private final InputStream body;
  private final long idleNanos;
  private final String servedVersion;
  private final ObjectMapper mapper;

  private final AtomicBoolean iterated = new AtomicBoolean();
  private final AtomicBoolean finished = new AtomicBoolean();
  private final SseDecoder sse = new SseDecoder();
  private final ArrayDeque<SseDecoder.Frame> frames = new ArrayDeque<>();
  private final byte[] buffer = new byte[BUFFER_BYTES];

  // Written by close() or the watchdog on another thread; read after a read ends.
  private volatile boolean closed;
  private volatile boolean timedOut;
  // The watchdog fires only while a read is pending: time the caller holds an event never counts.
  private volatile boolean readPending;
  private volatile long lastByteNanos;
  // The stream's current watchdog, which a test inspects rather than the shared queue.
  volatile ScheduledFuture<?> watchdog;

  private boolean ended;
  private E next;

  EventStream(
      Streams.Route route, Decoder<E> decoder, InputStream body, Duration idle, Settings settings) {
    this.route = route;
    this.decoder = decoder;
    this.body = body;
    this.idleNanos = idle.toNanos();
    this.servedVersion = settings.servedVersion().orElse(null);
    this.mapper = settings.mapper();
  }

  /**
   * What a stream shares with its client.
   *
   * @param servedVersion the {@code Lingara-Version} echo
   * @param mapper the client's JSON mapper
   */
  record Settings(Optional<String> servedVersion, ObjectMapper mapper) {}

  /**
   * Returns the {@code Lingara-Version} the server answered under, when it sent one.
   *
   * @return the served version
   */
  public Optional<String> servedVersion() {
    return Optional.ofNullable(servedVersion);
  }

  /**
   * Returns the one iterator over this stream's events.
   *
   * @throws IllegalStateException on a second call
   */
  @Override
  public Iterator<E> iterator() {
    if (!iterated.compareAndSet(false, true)) {
      throw new IllegalStateException("a Lingara EventStream can be iterated once");
    }
    return new Events();
  }

  /**
   * Returns the events as a {@code java.util.stream.Stream}, whose {@code close()} closes this
   * stream. It is this stream's one iteration.
   *
   * @return the events
   */
  public Stream<E> stream() {
    Spliterator<E> events = Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED);
    return StreamSupport.stream(events, false).onClose(this::close);
  }

  /** Ends the stream and closes the connection. Idempotent, and safe from any thread. */
  @Override
  public void close() {
    closed = true;
    finish();
  }

  private final class Events implements Iterator<E> {
    @Override
    public boolean hasNext() {
      if (next == null && !ended && !closed) {
        next = advance();
      }
      return next != null;
    }

    @Override
    public E next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      E event = next;
      next = null;
      return event;
    }
  }

  /** Reads until an event to yield, or the end; the watchdog runs only inside this call. */
  private E advance() {
    lastByteNanos = System.nanoTime();
    readPending = true;
    try {
      armWatchdog();
      while (true) {
        SseDecoder.Frame frame = frames.poll();
        if (frame == null) {
          read();
          continue;
        }
        Step<E> step = interpret(frame);
        if (step != null) {
          return step.end(this);
        }
      }
    } catch (RuntimeException failure) {
      finish();
      throw failure;
    } finally {
      readPending = false;
    }
  }

  /** One read into the decoder; the end of the body is classified by the stream's own state. */
  private void read() {
    int n;
    try {
      n = body.read(buffer);
    } catch (IOException e) {
      throw endedBy(e);
    }
    if (n < 0) {
      throw endedBy(null);
    }
    lastByteNanos = System.nanoTime();
    frames.addAll(sse.feed(buffer, 0, n));
  }

  /**
   * The flag decides, not the way the read ended: a body closed from another thread may throw or
   * return EOF depending on the JDK release.
   */
  private RuntimeException endedBy(IOException failure) {
    if (timedOut) {
      return new TransportException(TransportKind.TIMEOUT, failure);
    }
    if (closed || Thread.currentThread().isInterrupted()) {
      return Retry.cancelled();
    }
    if (failure == null) {
      return new TransportException(TransportKind.STREAM_ENDED_EARLY, null);
    }
    return ErrorMapper.transport(failure, true, List.of());
  }

  /** What one frame means: skip it (null), yield it, end quietly, or raise. */
  private Step<E> interpret(SseDecoder.Frame frame) {
    if (!route.events().contains(frame.event())) {
      return null;
    }
    JsonNode data;
    try {
      data = mapper.readTree(frame.data());
    } catch (IOException e) {
      throw new TransportException(TransportKind.MALFORMED_EVENT, e);
    }
    Streams.Ending ending = route.endsOn().get(frame.event());
    if (ending == Streams.Ending.RAISE) {
      throw streamError(data);
    }
    if (ending == Streams.Ending.QUIET) {
      return new Step<>(null, true);
    }
    E event = decode(frame.event(), data);
    return event == null ? null : new Step<>(event, ending == Streams.Ending.YIELD);
  }

  private E decode(String name, JsonNode data) {
    try {
      return decoder.decode(name, data, mapper);
    } catch (IOException | IllegalArgumentException e) {
      throw new TransportException(TransportKind.MALFORMED_EVENT, e);
    }
  }

  private ApiException streamError(JsonNode data) {
    return new ApiException(
        text(data, "code", "stream_error"),
        text(data, "message", "the stream reported an error"),
        text(data, "plan_id", null),
        servedVersion);
  }

  private static String text(JsonNode data, String field, String fallback) {
    JsonNode value = data == null ? null : data.get(field);
    return value != null && value.isTextual() ? value.asText() : fallback;
  }

  /**
   * An event to hand over, and whether the stream ends with it.
   *
   * @param event the event, or null for a quiet end
   * @param last whether the stream ends here
   */
  private record Step<E>(E event, boolean last) {
    E end(EventStream<E> stream) {
      if (last) {
        stream.ended = true;
        stream.finish();
      }
      return event;
    }
  }

  /** Closes the body and cancels the watchdog: every exit path runs this. */
  private void finish() {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    ended = true;
    ScheduledFuture<?> current = watchdog;
    if (current != null) {
      current.cancel(false);
    }
    try {
      body.close();
    } catch (IOException e) {
      // Nothing to do: the stream is over either way.
    }
  }

  private void armWatchdog() {
    if (watchdog == null && !finished.get()) {
      schedule(idleNanos);
    }
  }

  private void schedule(long delayNanos) {
    watchdog = SharedExecutor.scheduler().schedule(this::watch, delayNanos, TimeUnit.NANOSECONDS);
    if (finished.get()) {
      watchdog.cancel(false);
    }
  }

  /**
   * One self-rescheduling watchdog per stream: with a read pending and no byte for the timeout, it
   * marks the stream timed out and closes the body; otherwise it reschedules for what remains.
   */
  private void watch() {
    if (finished.get()) {
      return;
    }
    if (!readPending) {
      schedule(idleNanos);
      return;
    }
    long remaining = idleNanos - (System.nanoTime() - lastByteNanos);
    if (remaining > 0) {
      schedule(remaining);
      return;
    }
    timedOut = true;
    finish();
  }
}
