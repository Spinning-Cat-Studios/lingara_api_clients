package com.getlingara.kotlin

import java.net.URI
import java.time.Instant

/**
 * What a response under a deprecated API version says about it (CONTRACT.md K2). An unparseable
 * header leaves its parsed field `null`, never an error. It holds no secret, so it is a data class.
 */
public data class DeprecationNotice(
    /** The `Lingara-Version` echo. */
    val version: String?,
    /** `Deprecation`, parsed from `@<unix seconds>`. */
    val deprecatedAt: Instant?,
    /** `Sunset`, parsed from an IMF-fixdate. */
    val sunsetAt: Instant?,
    /** The `Link` header. */
    val link: Link?,
    /** The raw `Deprecation` header. */
    val deprecation: String,
    /** The raw `Sunset` header. */
    val sunset: String?,
) {
    /** A `Link` header: as sent, and its target resolved against the request URI (RFC 8288 §3.2). */
    public data class Link(
        val raw: String,
        val target: URI?,
    )
}
