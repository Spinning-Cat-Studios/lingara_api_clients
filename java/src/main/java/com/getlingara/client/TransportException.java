package com.getlingara.client;

/**
 * A call with no usable HTTP answer (CONTRACT.md K3). The cause, when there is one, is the JDK's
 * own failure; one whose text named a credential is replaced by a scrubbed stand-in, and the kind
 * is kept.
 */
public final class TransportException extends LingaraException {
  private static final long serialVersionUID = 1L;

  private final TransportKind kind;

  /**
   * A transport failure.
   *
   * @param kind why the call had no usable answer
   * @param cause the underlying failure, or null
   */
  public TransportException(TransportKind kind, Throwable cause) {
    super(
        "transport failure: "
            + kind.wireName()
            + (cause == null || cause.getMessage() == null ? "" : ": " + cause.getMessage()),
        cause);
    this.kind = kind;
  }

  /**
   * Returns why the call had no usable answer.
   *
   * @return the kind
   */
  public TransportKind kind() {
    return kind;
  }
}
