<?php

declare(strict_types=1);

namespace Lingara;

/**
 * K1's token source. The client calls only these two methods. ClientCredentials
 * is the one the library builds from `clientId` and `clientSecret`; a caller's
 * own replaces it through the client's `tokenSource` option.
 */
interface TokenSource
{
    /**
     * An access token, fresh enough to send.
     *
     * @throws Exception\LingaraException
     */
    public function token(): AccessToken;

    /**
     * Forgets $token only if it is still the cached one (compare-and-clear),
     * so a stale 401 cannot throw away a newer token.
     */
    public function invalidate(AccessToken $token): void;
}
