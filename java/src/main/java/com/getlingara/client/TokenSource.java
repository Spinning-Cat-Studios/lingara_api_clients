package com.getlingara.client;

/**
 * Where a client gets its access tokens (CONTRACT.md K1).
 *
 * <p>The client calls only these two methods. {@link ClientCredentialsTokenSource} is the one the
 * client builds from {@link LingaraClient.Builder#clientCredentials}; a caller may supply their own
 * through {@link LingaraClient.Builder#tokenSource}.
 */
public interface TokenSource {
  /**
   * Returns an access token, cached or freshly exchanged.
   *
   * @return the token
   * @throws LingaraException when no token can be had
   */
  AccessToken token();

  /**
   * Forgets {@code token}, but only if it is still the cached token, so a stale 401 cannot throw
   * away a newer one.
   *
   * @param token the token a request was refused with
   */
  void invalidate(AccessToken token);
}
