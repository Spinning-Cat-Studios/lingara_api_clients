package com.getlingara.client;

import java.util.Objects;

/**
 * An opaque access token. It renders as {@code [REDACTED]}; {@link #exposeSecret()} is the one way
 * to read it (CONTRACT.md K1, Redaction). Two tokens are equal when their values are, which is what
 * {@link TokenSource#invalidate} compares.
 *
 * <p>A final class and not a record, for the reason {@link ClientSecret} gives.
 */
public final class AccessToken {
  static final String REDACTED = "[REDACTED]";

  private final String value;

  /**
   * Wraps a raw access token, so a caller's own {@link TokenSource} can return one.
   *
   * @param value the token
   */
  public AccessToken(String value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  /**
   * Returns the raw token: the one accessor that does not redact.
   *
   * @return the token
   */
  public String exposeSecret() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof AccessToken token && token.value.equals(value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return REDACTED;
  }
}
