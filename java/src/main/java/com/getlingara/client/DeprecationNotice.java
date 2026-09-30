package com.getlingara.client;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * What a response under a deprecated API version says about it (CONTRACT.md K2). An unparseable
 * header leaves its parsed field empty, never an error.
 *
 * @param version the {@code Lingara-Version} echo
 * @param deprecatedAt {@code Deprecation}, parsed from {@code @<unix seconds>}
 * @param sunsetAt {@code Sunset}, parsed from an IMF-fixdate
 * @param link the {@code Link} header
 * @param deprecation the raw {@code Deprecation} header
 * @param sunset the raw {@code Sunset} header
 */
public record DeprecationNotice(
    Optional<String> version,
    Optional<Instant> deprecatedAt,
    Optional<Instant> sunsetAt,
    Optional<Link> link,
    String deprecation,
    Optional<String> sunset) {

  /**
   * A {@code Link} header.
   *
   * @param raw the header as sent
   * @param target its target resolved against the request URI (RFC 8288 §3.2)
   */
  public record Link(String raw, Optional<URI> target) {}
}
