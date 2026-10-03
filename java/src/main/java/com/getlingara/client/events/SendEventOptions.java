package com.getlingara.client.events;

/**
 * {@code sendEvent}'s options (ADR 30.9.26aa D8).
 *
 * <p>Without a key the library generates a UUIDv4 once per call and sends it on every retry of that
 * call. Supply your own when you may resend after a crash, since a generated key is gone once the
 * call returns: a resend under the same key gets the first answer back, and is not billed again. A
 * key reused for a different event also gets the first answer, so that event is lost.
 *
 * @param idempotencyKey 1 to 255 visible ASCII characters, or null to generate one
 */
public record SendEventOptions(String idempotencyKey) {
  /**
   * No options: the library generates the key.
   *
   * @return the options
   */
  public static SendEventOptions defaults() {
    return new SendEventOptions(null);
  }
}
