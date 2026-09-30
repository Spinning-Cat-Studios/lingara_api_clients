package com.getlingara.client;

import java.time.Duration;
import java.util.Optional;

/**
 * A {@code /v1} refusal, or a stream's {@code error} event, whose status is then 200 (CONTRACT.md
 * K3). {@link #getMessage()} is the envelope's {@code error} text, or the event's {@code message}.
 */
public final class ApiException extends LingaraException {
  private static final long serialVersionUID = 1L;

  private final int status;
  private final String code;
  private final Duration retryAfter;
  private final String planId;
  private final String servedVersion;

  /**
   * A {@code /v1} refusal.
   *
   * @param status the HTTP status
   * @param code the envelope's code, or {@code http_<status>} when the body is not the envelope
   * @param message the envelope's {@code error} text
   * @param retryAfter the {@code Retry-After} the library declined to wait for, or null
   * @param servedVersion the {@code Lingara-Version} echo, or null
   */
  public ApiException(
      int status, String code, String message, Duration retryAfter, String servedVersion) {
    super(message, null);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
    this.planId = null;
    this.servedVersion = servedVersion;
  }

  /**
   * A stream's {@code error} event: status 200, never retried.
   *
   * @param code the event's code
   * @param message the event's message
   * @param planId the event's {@code plan_id}, or null
   * @param servedVersion the stream's {@code Lingara-Version} echo, or null
   */
  public ApiException(String code, String message, String planId, String servedVersion) {
    super(message, null);
    this.status = 200;
    this.code = code;
    this.retryAfter = null;
    this.planId = planId;
    this.servedVersion = servedVersion;
  }

  /**
   * Returns the HTTP status: 200 for a stream's error event.
   *
   * @return the status
   */
  public int status() {
    return status;
  }

  /**
   * Returns the error code.
   *
   * @return the code
   */
  public String code() {
    return code;
  }

  /**
   * Returns the {@code Retry-After} the library declined to wait for.
   *
   * @return the wait
   */
  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }

  /**
   * Returns the plan id an error event carried.
   *
   * @return the plan id
   */
  public Optional<String> planId() {
    return Optional.ofNullable(planId);
  }

  /**
   * Returns the {@code Lingara-Version} the server answered under.
   *
   * @return the served version
   */
  public Optional<String> servedVersion() {
    return Optional.ofNullable(servedVersion);
  }

  @Override
  public String toString() {
    return "ApiException{status="
        + status
        + ", code="
        + code
        + ", message="
        + getMessage()
        + ", retryAfter="
        + retryAfter
        + ", planId="
        + planId
        + ", servedVersion="
        + servedVersion
        + "}";
  }
}
