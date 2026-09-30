package com.getlingara.client;

import java.time.Duration;
import java.util.Optional;

/** Any 503 whose body is not JSON: the service is under maintenance (CONTRACT.md K3). */
public final class MaintenanceException extends LingaraException {
  private static final long serialVersionUID = 1L;

  private final String body;
  private final Duration retryAfter;

  /**
   * A maintenance response.
   *
   * @param body the response text, at most 1 KiB, cut on a character boundary
   * @param retryAfter the {@code Retry-After}, or null
   */
  public MaintenanceException(String body, Duration retryAfter) {
    super("the Lingara API is under maintenance", null);
    this.body = body;
    this.retryAfter = retryAfter;
  }

  /**
   * Returns the response text, at most 1 KiB.
   *
   * @return the body
   */
  public String body() {
    return body;
  }

  /**
   * Returns the {@code Retry-After}, when the response carried one.
   *
   * @return the wait
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
