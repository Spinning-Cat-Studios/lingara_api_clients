package com.getlingara.client;

/**
 * Every error a call raises, cancellation aside (CONTRACT.md K3).
 *
 * <p>Unchecked, because {@code Iterator.hasNext()} and {@code java.util.stream} lambdas cannot
 * throw a checked exception, and a checked family would be wrapped at exactly the stream boundary
 * where a caller most needs its type. One {@code catch (LingaraException e)} handles all four;
 * {@code instanceof} patterns, or from Java 21 a pattern {@code switch}, tell them apart.
 * Cancellation is the JDK's own {@link java.util.concurrent.CancellationException}, outside this
 * family.
 *
 * <p>No subclass ever renders a client secret or an access token.
 */
public abstract sealed class LingaraException extends RuntimeException
    permits ApiException, OAuthException, MaintenanceException, TransportException {
  private static final long serialVersionUID = 1L;

  LingaraException(String message, Throwable cause) {
    super(message, cause);
  }
}
