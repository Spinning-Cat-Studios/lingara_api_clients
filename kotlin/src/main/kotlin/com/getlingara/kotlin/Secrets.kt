package com.getlingara.kotlin

internal const val REDACTED = "[REDACTED]"

/**
 * A client secret. It renders as `[REDACTED]`; [exposeSecret] is the one way to read it
 * (CONTRACT.md K1, Redaction).
 *
 * A final class holding a private `String`, never a value class or a data class (ADR 29.9.26s D7):
 * a value class is erased to its `String` wherever it is used unboxed, where a reflective renderer
 * would see the raw value, and a data class's `toString` would print it.
 */
public class ClientSecret(
    private val value: String,
) {
    /** Returns the raw secret: the one accessor that does not redact. */
    public fun exposeSecret(): String = value

    override fun equals(other: Any?): Boolean = other is ClientSecret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = REDACTED
}

/**
 * An opaque access token. It renders as `[REDACTED]`; [exposeSecret] is the one way to read it
 * (CONTRACT.md K1, Redaction). Two tokens are equal when their values are, which is what
 * [TokenSource.invalidate] compares. A final class, for the reason [ClientSecret] gives.
 */
public class AccessToken(
    private val value: String,
) {
    /** Returns the raw token: the one accessor that does not redact. */
    public fun exposeSecret(): String = value

    override fun equals(other: Any?): Boolean = other is AccessToken && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = REDACTED
}
