package com.getlingara.kotlin

/**
 * Where a client's access tokens come from (CONTRACT.md K1). The client asks for a token before
 * each request that needs one, and on a `401` invalidates the token it sent and asks once more.
 *
 * [ClientCredentialsTokenSource] is the default, built from `clientCredentials(…)`; a caller's own
 * replaces it through the builder's `tokenSource`.
 */
public interface TokenSource {
    /** Returns a token that is fresh now. Cancelling the caller never cancels a shared exchange. */
    public suspend fun token(): AccessToken

    /**
     * Forgets [token] only if it is still the cached one (compare-and-clear): a newer token, an
     * exchange in flight or an empty cache are left alone.
     */
    public fun invalidate(token: AccessToken)
}
