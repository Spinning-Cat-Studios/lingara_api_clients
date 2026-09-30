package com.getlingara.client;

import java.time.Duration;
import java.util.Optional;

/**
 * A token-endpoint refusal: RFC 6749 §5.2, or {@code http_<status>} when the body is not one
 * (CONTRACT.md K3).
 */
public final class OAuthException extends LingaraException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String error;
  private final String description;
  private final Duration retryAfter;

  /**
   * A token-endpoint refusal.
   *
   * @param status the HTTP status
   * @param error the RFC 6749 {@code error}, or {@code http_<status>}
   * @param description the {@code error_description}, or null
   * @param retryAfter the {@code Retry-After} the library declined to wait for, or null
   */
  public OAuthException(int status, String error, String description, Duration retryAfter) {
    super(
        "token endpoint: "
            + error
            + (description == null ? "" : ": " + description)
            + " (HTTP "
            + status
            + ")",
        null);
    this.status = status;
    this.error = error;
    this.description = description;
    this.retryAfter = retryAfter;
  }

  /**
   * Returns the HTTP status.
   *
   * @return the status
   */
  public int status() {
    return status;
  }

  /**
   * Returns the RFC 6749 {@code error}, or {@code http_<status>}.
   *
   * @return the error
   */
  public String error() {
    return error;
  }

  /**
   * Returns the {@code error_description}.
   *
   * @return the description
   */
  public Optional<String> description() {
    return Optional.ofNullable(description);
  }

  /**
   * Returns the {@code Retry-After} the library declined to wait for.
   *
   * @return the wait
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
