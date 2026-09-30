package com.getlingara.client.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.ApiException;
import com.getlingara.client.LingaraException;
import com.getlingara.client.MaintenanceException;
import com.getlingara.client.OAuthException;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import javax.net.ssl.SSLException;

/**
 * Maps a refused response, or a failed request, to its K3 exception (CONTRACT.md K3; ADR 29.9.26r
 * D6).
 */
public final class ErrorMapper {
  private static final int MAINTENANCE_BODY_BYTES = 1024;
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Which endpoint refused: the two map their bodies differently. */
  public enum Endpoint {
    /** A {@code /v1} operation. */
    V1,
    /** {@code /oauth/token}. */
    TOKEN
  }

  private ErrorMapper() {}

  /**
   * Maps a non-2xx response in the contract's precedence: a non-JSON 503 from either endpoint is
   * maintenance first, then the endpoint's own body shape.
   *
   * @param endpoint which endpoint answered
   * @param status the HTTP status
   * @param headers the response's headers
   * @param body the response's body, or its first bytes
   * @param now the clock's instant, for an HTTP-date {@code Retry-After}
   * @return the exception to throw
   */
  public static LingaraException refusal(
      Endpoint endpoint, int status, HttpHeaders headers, byte[] body, Instant now) {
    Duration retryAfter = Retry.retryAfter(headers, now).orElse(null);
    if (status == 503 && !isJson(headers)) {
      return new MaintenanceException(truncate(body, MAINTENANCE_BODY_BYTES), retryAfter);
    }
    JsonNode json = parse(body);
    if (endpoint == Endpoint.TOKEN) {
      String error = text(json, "error");
      return error == null
          ? new OAuthException(status, "http_" + status, null, retryAfter)
          : new OAuthException(status, error, text(json, "error_description"), retryAfter);
    }
    String served = headers.firstValue("Lingara-Version").orElse(null);
    String code = text(json, "code");
    String message = text(json, "error");
    if (code == null || message == null) {
      return new ApiException(status, "http_" + status, "HTTP " + status, retryAfter, served);
    }
    return new ApiException(status, code, message, retryAfter, served);
  }

  /**
   * Maps a failed send ({@code afterHeaders} false) or body read (true), in D6's order, and scrubs
   * any credential from the cause.
   *
   * @param failure what the JDK threw
   * @param afterHeaders whether the response headers had arrived
   * @param secrets the values no cause may carry
   * @return the exception to throw
   */
  public static TransportException transport(
      Throwable failure, boolean afterHeaders, Collection<String> secrets) {
    return new TransportException(kind(failure, afterHeaders), scrub(failure, secrets));
  }

  static TransportKind kind(Throwable failure, boolean afterHeaders) {
    if (inChain(failure, SSLException.class)) {
      return TransportKind.TLS;
    }
    if (inChain(failure, HttpConnectTimeoutException.class)
        || inChain(failure, ConnectException.class)
        || inChain(failure, UnresolvedAddressException.class)) {
      return TransportKind.CONNECT;
    }
    if (inChain(failure, HttpTimeoutException.class)) {
      return TransportKind.TIMEOUT;
    }
    return afterHeaders ? TransportKind.RESET : TransportKind.CONNECT;
  }

  private static boolean inChain(Throwable failure, Class<? extends Throwable> type) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (type.isInstance(t)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the failure, or a stand-in when any message in its chain names a credential. The JDK's
   * HTTP client does not echo request headers or bodies, so this is defence in depth.
   */
  static Throwable scrub(Throwable failure, Collection<String> secrets) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      String message = String.valueOf(t.getMessage());
      if (secrets.stream().anyMatch(s -> s != null && !s.isEmpty() && message.contains(s))) {
        return new IOException("the underlying error was withheld: it contained a credential");
      }
    }
    return failure;
  }

  /**
   * Returns the {@code Content-Type}'s media type, lower-cased, with its parameters dropped.
   *
   * @param headers the response's headers
   * @return the media type, or the empty string
   */
  public static String mediaType(HttpHeaders headers) {
    String value = headers.firstValue("Content-Type").orElse("");
    int semicolon = value.indexOf(';');
    return (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
  }

  static boolean isJson(HttpHeaders headers) {
    String media = mediaType(headers);
    return media.equals("application/json") || media.endsWith("+json");
  }

  /** At most {@code max} bytes of UTF-8, cut on a character boundary. */
  static String truncate(byte[] body, int max) {
    int end = body.length;
    if (end > max) {
      end = max;
      while (end > 0 && (body[end] & 0xC0) == 0x80) {
        end--;
      }
    }
    return new String(Arrays.copyOf(body, end), StandardCharsets.UTF_8);
  }

  private static JsonNode parse(byte[] body) {
    try {
      return JSON.readTree(body);
    } catch (IOException e) {
      return null;
    }
  }

  private static String text(JsonNode json, String field) {
    JsonNode value = json == null ? null : json.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
