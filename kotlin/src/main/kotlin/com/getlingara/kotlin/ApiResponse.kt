package com.getlingara.kotlin

/**
 * A JSON operation's [body] and the API version it was served under (CONTRACT.md K2).
 *
 * Not called `Response`, so it never collides with `retrofit2.Response` or a web framework's
 * `Response` in a caller's imports, and it matches the Java library's name.
 */
public class ApiResponse<T> internal constructor(
    /** The decoded body. */
    public val body: T,
    /** The `Lingara-Version` the server answered under, when it sent one. */
    public val servedVersion: String?,
) {
    override fun toString(): String = "ApiResponse(servedVersion=$servedVersion, body=$body)"
}
