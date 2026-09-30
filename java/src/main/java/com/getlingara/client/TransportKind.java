package com.getlingara.client;

import java.util.Locale;

/** Why a call had no usable HTTP answer: CONTRACT.md K3's seven kinds. */
public enum TransportKind {
  /** A connect failure, a connect timeout, or any failure before the response headers. */
  CONNECT,
  /** A TLS failure. */
  TLS,
  /** A failure while reading a body. */
  RESET,
  /** A caller's {@code requestTimeout}, the token request timeout, or the stream idle timeout. */
  TIMEOUT,
  /** EOF before a stream's terminal event. */
  STREAM_ENDED_EARLY,
  /** A body that does not decode, or a non-SSE {@code 200} on a stream. */
  MALFORMED_RESPONSE,
  /** A known stream event whose data does not decode. */
  MALFORMED_EVENT;

  /**
   * Returns the kind as the contract spells it, such as {@code stream_ended_early}.
   *
   * @return the contract's spelling
   */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
