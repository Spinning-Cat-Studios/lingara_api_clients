package com.getlingara.client.events;

import com.getlingara.client.events.WebhookVerificationException.Reason;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies a Lingara webhook delivery: the Standard Webhooks scheme, keyed on {@code lgr_whsec_}
 * secrets (CONTRACT.md appendix W; ADR 30.9.26aa D4). It is native, on {@code javax.crypto.Mac},
 * {@code MessageDigest.isEqual} and {@code java.util.Base64}, so the library gains no dependency.
 *
 * <p>Hand it the body exactly as received, as bytes, before anything parses it: a servlet's {@code
 * request.getInputStream().readAllBytes()}, or Spring's {@code @RequestBody byte[] body}. Then
 * answer {@code 2xx} quickly and deduplicate by {@link Event#id()}: delivery is at least once.
 *
 * <pre>{@code
 * Webhook webhook = Webhook.of(System.getenv("LINGARA_WEBHOOK_SECRET"));
 * Event event = webhook.verify(body, headers);
 * }</pre>
 *
 * <p>It stores nothing and is safe for concurrent use. Its secrets are never rendered.
 */
public final class Webhook {
  private static final String PREFIX = "lgr_whsec_";
  private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");
  private static final Pattern DIGITS = Pattern.compile("[0-9]+");
  private static final int MIN_KEY_BYTES = 24;
  private static final BigInteger TOLERANCE_SECONDS = BigInteger.valueOf(300);

  private final List<byte[]> keys;
  private final Clock clock;

  private Webhook(List<byte[]> keys, Clock clock) {
    this.keys = keys;
    this.clock = clock;
  }

  /**
   * A verifier for one secret, or two while a rotation has both live.
   *
   * @param secrets each {@code lgr_whsec_} followed by padded standard base64 of at least 24 bytes
   * @return the verifier
   * @throws IllegalStateException for no secret, or any secret not of that shape
   */
  public static Webhook of(String... secrets) {
    return of(List.of(secrets), Clock.systemUTC());
  }

  /**
   * A verifier reading {@code now} from {@code clock}, the testing seam the client's builder also
   * takes.
   *
   * @param secrets as {@link #of(String...)}
   * @param clock what a delivery's timestamp is checked against
   * @return the verifier
   * @throws IllegalStateException for no secret, or any secret not of that shape
   */
  public static Webhook of(List<String> secrets, Clock clock) {
    if (secrets.isEmpty()) {
      throw new IllegalStateException("a Webhook needs at least one secret");
    }
    List<byte[]> keys = new ArrayList<>();
    for (String secret : secrets) {
      keys.add(key(secret));
    }
    return new Webhook(List.copyOf(keys), java.util.Objects.requireNonNull(clock, "clock"));
  }

  /**
   * The HMAC key: the remainder after {@code lgr_whsec_}, matched as padded standard base64 before
   * it is decoded, because decoders differ in leniency.
   */
  private static byte[] key(String secret) {
    String rest =
        secret != null && secret.startsWith(PREFIX) ? secret.substring(PREFIX.length()) : "";
    if (rest.length() % 4 != 0 || !BASE64.matcher(rest).matches()) {
      throw new IllegalStateException(
          "a Lingara webhook secret is lgr_whsec_ followed by padded base64");
    }
    byte[] key = Base64.getDecoder().decode(rest);
    if (key.length < MIN_KEY_BYTES) {
      throw new IllegalStateException("a Lingara webhook secret decodes to at least 24 bytes");
    }
    return key;
  }

  /**
   * Verifies a delivery and parses it into an {@link Event}: a type this library does not know is
   * an {@link UnknownEvent}, which you should still acknowledge.
   *
   * @param body the body exactly as received
   * @param headers the request's headers, any case: a servlet's or {@code HttpHeaders.map()}
   * @return the event
   * @throws WebhookVerificationException when the delivery fails verification
   */
  public Event verify(byte[] body, Map<String, List<String>> headers) {
    String id = check(body, headers);
    Event event;
    try {
      event = Event.parse(new String(body, StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      // No cause: a JSON parser's message can quote the body.
      throw new WebhookVerificationException(
          Reason.MALFORMED_PAYLOAD, "the body is not an event envelope", null);
    }
    if (!event.id().equals(id)) {
      throw new WebhookVerificationException(
          Reason.MALFORMED_PAYLOAD, "the envelope's id is not webhook-id", null);
    }
    return event;
  }

  /**
   * Verifies a delivery's signature and nothing else, for a signed body that is not an event
   * envelope, such as an app-kit request.
   *
   * @param body the body exactly as received
   * @param headers the request's headers, any case
   * @throws WebhookVerificationException when the signature does not verify; never {@code
   *     MALFORMED_PAYLOAD}
   */
  public void verifySignature(byte[] body, Map<String, List<String>> headers) {
    check(body, headers);
  }

  /** Appendix W steps 2–5; returns {@code webhook-id}. */
  private String check(byte[] body, Map<String, List<String>> headers) {
    String id = header(headers, "webhook-id");
    String timestamp = header(headers, "webhook-timestamp");
    String signatures = header(headers, "webhook-signature");
    if (id == null || timestamp == null || signatures == null) {
      throw new WebhookVerificationException(
          Reason.MISSING_HEADER, "a webhook-* header is missing", null);
    }
    checkTimestamp(timestamp);
    byte[] signed = signedContent(id, timestamp, body);
    for (byte[] key : keys) {
      byte[] expected = hmac(key, signed);
      for (byte[] candidate : v1Signatures(signatures)) {
        if (MessageDigest.isEqual(expected, candidate)) {
          return id;
        }
      }
    }
    throw new WebhookVerificationException(
        Reason.NO_MATCHING_SIGNATURE, "no v1 signature matches a secret", null);
  }

  private void checkTimestamp(String timestamp) {
    if (!DIGITS.matcher(timestamp).matches()) {
      throw new WebhookVerificationException(
          Reason.MALFORMED_HEADER, "webhook-timestamp is not whole seconds", null);
    }
    BigInteger at = new BigInteger(timestamp);
    BigInteger now = BigInteger.valueOf(clock.instant().getEpochSecond());
    if (at.compareTo(now.subtract(TOLERANCE_SECONDS)) < 0) {
      throw new WebhookVerificationException(
          Reason.TIMESTAMP_TOO_OLD, "webhook-timestamp is more than 300 s old", null);
    }
    if (at.compareTo(now.add(TOLERANCE_SECONDS)) > 0) {
      throw new WebhookVerificationException(
          Reason.TIMESTAMP_TOO_NEW, "webhook-timestamp is more than 300 s ahead", null);
    }
  }

  /** The first value of a header, its name matched case-insensitively; null when absent. */
  private static String header(Map<String, List<String>> headers, String name) {
    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey()) && e.getValue() != null && !e.getValue().isEmpty()) {
        return e.getValue().get(0);
      }
    }
    return null;
  }

  private static byte[] signedContent(String id, String timestamp, byte[] body) {
    byte[] prefix = (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[prefix.length + body.length];
    System.arraycopy(prefix, 0, out, 0, prefix.length);
    System.arraycopy(body, 0, out, prefix.length, body.length);
    return out;
  }

  /** Every {@code v1,<base64>} element that decodes; another version, or bad base64, is skipped. */
  private static List<byte[]> v1Signatures(String header) {
    List<byte[]> out = new ArrayList<>();
    for (String element : header.split(" ", -1)) {
      if (element.startsWith("v1,")) {
        try {
          out.add(Base64.getDecoder().decode(element.substring(3)));
        } catch (IllegalArgumentException e) {
          // Not base64: skipped, as the specification says.
        }
      }
    }
    return out;
  }

  private static byte[] hmac(byte[] key, byte[] content) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(content);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is part of every Java runtime", e);
    }
  }

  @Override
  public String toString() {
    return "Webhook{secrets=" + keys.size() + "}";
  }
}
