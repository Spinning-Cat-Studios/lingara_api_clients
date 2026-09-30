package com.getlingara.client;

import java.util.Objects;

/**
 * An OAuth client secret. It renders as {@code [REDACTED]}; {@link #exposeSecret()} is the one way
 * to read it (CONTRACT.md K1, Redaction).
 *
 * <p>A final class and not a record: a record's accessor would be a second public road to the
 * value, and its generated {@code toString} would print it.
 */
public final class ClientSecret {
  private final String value;

  /**
   * Wraps a raw client secret.
   *
   * @param value the secret
   */
  public ClientSecret(String value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  /**
   * Returns the raw secret: the one accessor that does not redact.
   *
   * @return the secret
   */
  public String exposeSecret() {
    return value;
  }

  @Override
  public String toString() {
    return AccessToken.REDACTED;
  }
}
