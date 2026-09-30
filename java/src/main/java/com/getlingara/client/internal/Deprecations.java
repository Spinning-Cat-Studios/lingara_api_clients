package com.getlingara.client.internal;

import com.getlingara.client.DeprecationNotice;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * K2, per client: reads the served version off each response, reports a deprecation once per
 * response to the hook or, with no hook, warns once per version id, and warns once per served id
 * that is not the version the models were generated from (CONTRACT.md K2; ADR 30.9.26a).
 */
public final class Deprecations {
  private static final System.Logger LOG = System.getLogger("com.getlingara.client");

  private final Consumer<DeprecationNotice> hook;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();
  // Its own set: sharing `warned` would let a version that is both deprecated and mismatched warn
  // only once in total.
  private final Set<String> mismatched = ConcurrentHashMap.newKeySet();

  /**
   * One client's observer.
   *
   * @param hook the caller's deprecation hook, or null for one warning per version id
   */
  public Deprecations(Consumer<DeprecationNotice> hook) {
    this.hook = hook;
  }

  /**
   * Reports any deprecation or version mismatch, then returns the {@code Lingara-Version} echo.
   *
   * @param headers the response's headers
   * @param requestUri the request's URI, which a relative {@code Link} resolves against
   * @return the served version
   */
  public Optional<String> observe(HttpHeaders headers, URI requestUri) {
    notice(headers, requestUri).ifPresent(this::report);
    Optional<String> served = headers.firstValue("Lingara-Version");
    served.ifPresent(this::checkGenerated);
    return served;
  }

  /**
   * Returns the notice a response carries, or empty when it has no {@code Deprecation} header.
   *
   * @param headers the response's headers
   * @param requestUri the request's URI
   * @return the notice
   */
  public static Optional<DeprecationNotice> notice(HttpHeaders headers, URI requestUri) {
    Optional<String> raw = headers.firstValue("Deprecation");
    if (raw.isEmpty()) {
      return Optional.empty();
    }
    Optional<String> sunset = headers.firstValue("Sunset");
    return Optional.of(
        new DeprecationNotice(
            headers.firstValue("Lingara-Version"),
            deprecatedAt(raw.get()),
            sunset.flatMap(Deprecations::imfFixdate),
            headers.firstValue("Link").map(link -> link(link, requestUri)),
            raw.get(),
            sunset));
  }

  private void report(DeprecationNotice notice) {
    if (hook == null) {
      warnOnce(notice);
      return;
    }
    try {
      hook.accept(notice);
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.DEBUG, "the Lingara deprecation hook threw; the call continues", e);
    }
  }

  private void warnOnce(DeprecationNotice notice) {
    // An absent echo counts as one id: the empty string.
    String id = notice.version().orElse("");
    if (warned.add(id)) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Lingara API version "
              + (id.isEmpty() ? "(unnamed)" : id)
              + " is deprecated"
              + notice.sunset().map(s -> "; sunset " + s).orElse("")
              + ". See GET /v1/versions.");
    }
  }

  private void checkGenerated(String served) {
    String generated = SpecVersion.GENERATED_FOR_VERSION;
    if (!served.equals(generated) && mismatched.add(served)) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Lingara API version "
              + served
              + " served this response, but this library's models were generated for "
              + generated
              + "; response shapes may differ. Pin the OAuth client to "
              + generated
              + " or upgrade the library.");
    }
  }

  static Optional<Instant> deprecatedAt(String value) {
    String trimmed = value.trim();
    if (!trimmed.startsWith("@") || !trimmed.substring(1).matches("-?\\d{1,18}")) {
      return Optional.empty();
    }
    return Optional.of(Instant.ofEpochSecond(Long.parseLong(trimmed.substring(1))));
  }

  static Optional<Instant> imfFixdate(String value) {
    try {
      return Optional.of(
          ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  static DeprecationNotice.Link link(String raw, URI requestUri) {
    String trimmed = raw.trim();
    int close = trimmed.indexOf('>');
    if (!trimmed.startsWith("<") || close < 0) {
      return new DeprecationNotice.Link(raw, Optional.empty());
    }
    try {
      return new DeprecationNotice.Link(
          raw, Optional.of(requestUri.resolve(trimmed.substring(1, close))));
    } catch (IllegalArgumentException e) {
      return new DeprecationNotice.Link(raw, Optional.empty());
    }
  }
}
