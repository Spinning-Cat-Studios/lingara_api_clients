package com.getlingara.client.events;

import java.util.Locale;

/**
 * A webhook delivery that failed verification (CONTRACT.md appendix W; ADR 30.9.26aa D4).
 *
 * <p>It is deliberately not a {@link com.getlingara.client.LingaraException}: no Lingara server
 * answered anything, and a {@code catch (LingaraException e)} around API calls must not also
 * swallow a forged webhook. Its message never contains a secret, a signature or the body.
 */
public final class WebhookVerificationException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Why a delivery failed verification: appendix W's six reasons. */
  public enum Reason {
    /** A {@code webhook-id}, {@code webhook-timestamp} or {@code webhook-signature} is absent. */
    MISSING_HEADER,
    /** {@code webhook-timestamp} is not one or more ASCII digits. */
    MALFORMED_HEADER,
    /** The timestamp is more than 300 s before now. */
    TIMESTAMP_TOO_OLD,
    /** The timestamp is more than 300 s after now. */
    TIMESTAMP_TOO_NEW,
    /** No {@code v1} signature matches any secret. */
    NO_MATCHING_SIGNATURE,
    /** The signature matched, but the body is not an event envelope with that id. */
    MALFORMED_PAYLOAD;

    /**
     * Returns the reason as the contract spells it, such as {@code no_matching_signature}.
     *
     * @return the contract's spelling
     */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private final Reason reason;

  WebhookVerificationException(Reason reason, String message, Throwable cause) {
    super(reason.wireName() + ": " + message, cause);
    this.reason = reason;
  }

  /**
   * Returns why verification failed.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}
