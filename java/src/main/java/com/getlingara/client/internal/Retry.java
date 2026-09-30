package com.getlingara.client.internal;

import com.getlingara.client.Sleeper;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * K4's {@code Retry-After} loop around one HTTP request (CONTRACT.md K4). The one 401 retry around
 * a {@code /v1} call is {@code LingaraClient}'s.
 *
 * <p>Each HTTP request has its own budget of {@code maxAttempts}. The decision is made on the
 * status line and headers alone, before any byte of the body reaches the caller. A transport
 * failure is never retried: it is raised by the attempt itself.
 */
public final class Retry {
  /** Clamps a huge delta-seconds, which is above any cap either way. */
  private static final long MAX_SECONDS = 1L << 32;

  private Retry() {}

  /**
   * K4's knobs and the two seams they are read and slept against.
   *
   * @param maxAttempts tries per request, the first included; 1 turns retries off
   * @param cap the longest {@code Retry-After} worth waiting for
   * @param clock what an HTTP-date {@code Retry-After} is read against
   * @param sleeper what waits it out
   */
  public record Policy(int maxAttempts, Duration cap, Clock clock, Sleeper sleeper) {}

  /**
   * One try of a request.
   *
   * @param <T> the body's type
   */
  @FunctionalInterface
  public interface Attempt<T> {
    /**
     * Sends the request once.
     *
     * @return the response, whatever its status
     */
    HttpResponse<T> send();
  }

  /**
   * Sends until the answer is not a retryable 429 or 503, or the attempts run out, and returns the
   * last response. A retried response's body is closed unread.
   *
   * @param <T> the body's type
   * @param policy the knobs
   * @param attempt one try
   * @return the last response
   * @throws CancellationException when the thread is interrupted during a wait
   */
  public static <T> HttpResponse<T> withRetries(Policy policy, Attempt<T> attempt) {
    for (int tries = 1; ; tries++) {
      HttpResponse<T> response = attempt.send();
      Optional<Duration> wait = retryWait(policy, response, tries);
      if (wait.isEmpty()) {
        return response;
      }
      discard(response);
      sleep(policy.sleeper(), wait.get());
    }
  }

  static Optional<Duration> retryWait(Policy policy, HttpResponse<?> response, int tries) {
    int status = response.statusCode();
    if ((status != 429 && status != 503) || tries >= policy.maxAttempts()) {
      return Optional.empty();
    }
    return retryAfter(response.headers(), policy.clock().instant())
        .filter(wait -> wait.compareTo(policy.cap()) <= 0);
  }

  /**
   * Reads {@code Retry-After} as delta-seconds, or as an HTTP-date against {@code now}: {@code
   * max(0, date − now)}, rounded up to a whole second. Absent or unreadable is empty.
   *
   * @param headers the response's headers
   * @param now the clock's instant
   * @return the wait
   */
  public static Optional<Duration> retryAfter(HttpHeaders headers, Instant now) {
    Optional<String> header = headers.firstValue("Retry-After").map(String::trim);
    if (header.isEmpty() || header.get().isEmpty()) {
      return Optional.empty();
    }
    String value = header.get();
    if (value.chars().allMatch(Character::isDigit)) {
      String digits = value.length() > 12 ? String.valueOf(MAX_SECONDS) : value;
      return Optional.of(Duration.ofSeconds(Math.min(Long.parseLong(digits), MAX_SECONDS)));
    }
    try {
      Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      long millis = Math.max(0, Duration.between(now, at).toMillis());
      return Optional.of(Duration.ofSeconds((millis + 999) / 1000));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /**
   * Waits through the sleeper; an interrupt ends the wait and the call.
   *
   * @param sleeper the seam
   * @param wait how long
   * @throws CancellationException when the thread is interrupted, its flag restored
   */
  public static void sleep(Sleeper sleeper, Duration wait) {
    try {
      sleeper.sleep(wait);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw cancelled();
    }
    if (Thread.currentThread().isInterrupted()) {
      throw cancelled();
    }
  }

  /**
   * The JDK's cancellation signal, for an interrupted call.
   *
   * @return the exception to throw
   */
  public static CancellationException cancelled() {
    return new CancellationException("the Lingara call was cancelled");
  }

  /**
   * Closes a streamed response's body unread; a buffered one needs nothing.
   *
   * @param response the response to drop
   */
  public static void discard(HttpResponse<?> response) {
    if (response.body() instanceof InputStream in) {
      try {
        in.close();
      } catch (IOException e) {
        // Nothing to do: the response is being dropped.
      }
    }
  }
}
