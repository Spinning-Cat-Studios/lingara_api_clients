package com.getlingara.client;

import java.util.Optional;

/**
 * A JSON operation's body and the API version it was served under (CONTRACT.md K2).
 *
 * <p>Not called {@code Response}, so it never collides with {@code jakarta.ws.rs.core.Response} in
 * a caller's imports.
 *
 * @param <T> the body's type
 */
public final class ApiResponse<T> {
  private final T body;
  private final String servedVersion;

  ApiResponse(T body, Optional<String> servedVersion) {
    this.body = body;
    this.servedVersion = servedVersion.orElse(null);
  }

  /**
   * Returns the decoded body.
   *
   * @return the body
   */
  public T body() {
    return body;
  }

  /**
   * Returns the {@code Lingara-Version} the server answered under, when it sent one.
   *
   * @return the served version
   */
  public Optional<String> servedVersion() {
    return Optional.ofNullable(servedVersion);
  }

  @Override
  public String toString() {
    return "ApiResponse{servedVersion=" + servedVersion + ", body=" + body + "}";
  }
}
