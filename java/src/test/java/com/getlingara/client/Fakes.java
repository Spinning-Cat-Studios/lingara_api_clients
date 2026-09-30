package com.getlingara.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** The unit suite's fakes: an in-process HTTP server, a settable clock and a recording sleeper. */
final class Fakes {
  private Fakes() {}

  /** One request as the server saw it. */
  record Seen(String method, String path, Map<String, List<String>> headers, String body) {
    String header(String name) {
      List<String> values = headers.get(name);
      return values == null ? null : values.get(0);
    }
  }

  /** Answers one exchange. */
  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  /** {@code com.sun.net.httpserver} on 127.0.0.1:0, recording every request. */
  static final class Server implements AutoCloseable {
    final HttpServer http;
    final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    Server() throws IOException {
      http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
      http.start();
    }

    Server on(String path, Handler handler) {
      http.createContext(
          path,
          exchange -> {
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(
                new Seen(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    lowerCased(exchange),
                    body));
            hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
            try {
              handler.handle(exchange);
            } finally {
              exchange.close();
            }
          });
      return this;
    }

    int hits(String path) {
      AtomicInteger n = hits.get(path);
      return n == null ? 0 : n.get();
    }

    URI uri() {
      return URI.create("http://127.0.0.1:" + http.getAddress().getPort());
    }

    URI uri(String path) {
      return URI.create(uri() + path);
    }

    @Override
    public void close() {
      http.stop(0);
    }

    private static Map<String, List<String>> lowerCased(HttpExchange exchange) {
      Map<String, List<String>> out = new ConcurrentHashMap<>();
      exchange
          .getRequestHeaders()
          .forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
      return out;
    }
  }

  /** Writes a whole response. */
  static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (contentType != null) {
      exchange.getResponseHeaders().set("Content-Type", contentType);
    }
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
  }

  static void json(HttpExchange exchange, int status, String body) throws IOException {
    respond(exchange, status, "application/json", body);
  }

  /** A token endpoint answering {@code access_token} with {@code expires_in}. */
  static Handler token(String accessToken, long expiresIn) {
    return exchange ->
        json(
            exchange,
            200,
            "{\"access_token\":\""
                + accessToken
                + "\",\"token_type\":\"Bearer\",\"expires_in\":"
                + expiresIn
                + "}");
  }

  /** Answers the n-th request with the n-th handler, and every later one with the last. */
  static Handler script(Handler... steps) {
    AtomicInteger next = new AtomicInteger();
    return exchange -> steps[Math.min(next.getAndIncrement(), steps.length - 1)].handle(exchange);
  }

  /** A status with one header and a JSON body. */
  static Handler status(int status, String header, String value, String body) {
    return exchange -> {
      if (header != null) {
        exchange.getResponseHeaders().set(header, value);
      }
      json(exchange, status, body);
    };
  }

  static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** A clock that moves only when a test says so. */
  static final class SettableClock extends Clock {
    final AtomicLong millis = new AtomicLong(1_790_000_000_000L);

    void advance(Duration by) {
      millis.addAndGet(by.toMillis());
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }
  }

  /** Records each requested wait and returns at once. */
  static final class RecordingSleeper implements Sleeper {
    final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void sleep(Duration duration) {
      sleeps.add(duration);
    }
  }
}
