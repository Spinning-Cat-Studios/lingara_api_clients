package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.getlingara.client.internal.Retry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientCredentialsTokenSourceTest {
  private final Fakes.SettableClock clock = new Fakes.SettableClock();

  private ClientCredentialsTokenSource source(Fakes.Server server, Duration timeout) {
    Retry.Policy policy =
        new Retry.Policy(3, Duration.ofSeconds(60), clock, new Fakes.RecordingSleeper());
    return new ClientCredentialsTokenSource(
        "lgr_cid_test",
        new ClientSecret("lgr_cs_test"),
        new ClientCredentialsTokenSource.Exchange(
            HttpClient.newHttpClient(),
            server.uri("/oauth/token"),
            "lingara-java/0.0.0 (jvm/17)",
            false,
            List.of(),
            policy,
            timeout));
  }

  private ClientCredentialsTokenSource source(Fakes.Server server) {
    return source(server, Duration.ofSeconds(30));
  }

  /** A token endpoint numbering its tokens, so a new exchange is visible in the value. */
  private static Fakes.Handler numbered(long expiresIn, long delayMillis) {
    AtomicInteger n = new AtomicInteger();
    return exchange -> {
      Fakes.sleep(delayMillis);
      Fakes.token("lgr_at_" + n.incrementAndGet(), expiresIn).handle(exchange);
    };
  }

  /** Runs {@code call} on n threads released together; each result or thrown exception. */
  private static List<Object> together(int n, Supplier<Object> call) throws InterruptedException {
    List<Object> results = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch start = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Thread t =
          new Thread(
              () -> {
                try {
                  start.await();
                  results.add(call.get());
                } catch (RuntimeException | InterruptedException e) {
                  results.add(e);
                }
              });
      threads.add(t);
      t.start();
    }
    start.countDown();
    for (Thread t : threads) {
      t.join();
    }
    return results;
  }

  /**
   * 29.9.26r AC3: eight concurrent token() calls cause one exchange; when it fails, all eight throw
   * the same exception instance and nothing is cached.
   */
  @Test
  void singleFlightSharesOneExchangeAndCachesNoFailure() throws Exception {
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", numbered(3600, 200))) {
      ClientCredentialsTokenSource tokens = source(server);
      List<Object> got = together(8, tokens::token);
      assertEquals(1, server.hits("/oauth/token"));
      got.forEach(token -> assertEquals(got.get(0), token));
    }
    Fakes.Handler failing =
        exchange -> {
          Fakes.sleep(200);
          Fakes.respond(exchange, 500, "text/html", "<html>oops</html>");
        };
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", failing)) {
      ClientCredentialsTokenSource tokens = source(server);
      List<Object> got = together(8, tokens::token);
      assertEquals(1, server.hits("/oauth/token"));
      OAuthException first = assertInstanceOf(OAuthException.class, got.get(0));
      assertEquals("http_500", first.error());
      got.forEach(e -> assertSame(first, e));
      assertThrows(OAuthException.class, tokens::token);
      assertEquals(2, server.hits("/oauth/token"), "a failure is never cached");
    }
  }

  /**
   * 29.9.26r AC4: with expires_in 3600 a token is reused at 3539 s and replaced at 3541 s after
   * send, and with expires_in 40 it is stale at 20 s.
   */
  @Test
  void refreshesAtMinOfSixtySecondsAndHalfTheLifetime() throws Exception {
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", numbered(3600, 0))) {
      ClientCredentialsTokenSource tokens = source(server);
      AccessToken first = tokens.token();
      clock.advance(Duration.ofSeconds(3539));
      assertEquals(first, tokens.token());
      clock.advance(Duration.ofSeconds(2));
      assertNotEquals(first, tokens.token());
      assertEquals(2, server.hits("/oauth/token"));
    }
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", numbered(40, 0))) {
      ClientCredentialsTokenSource tokens = source(server);
      AccessToken first = tokens.token();
      clock.advance(Duration.ofSeconds(19));
      assertEquals(first, tokens.token());
      clock.advance(Duration.ofSeconds(1));
      assertNotEquals(first, tokens.token());
    }
  }

  /**
   * 29.9.26r AC5: invalidate of an older token leaves a newer cached token in place, and a waiter
   * that invalidates the token its flight has just returned clears the cache.
   */
  @Test
  void invalidateIsCompareAndClear() throws Exception {
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", numbered(3600, 0))) {
      ClientCredentialsTokenSource tokens = source(server);
      AccessToken older = tokens.token();
      tokens.invalidate(older);
      AccessToken newer = tokens.token();
      assertEquals(2, server.hits("/oauth/token"));
      tokens.invalidate(older);
      assertEquals(newer, tokens.token());
      assertEquals(2, server.hits("/oauth/token"), "a stale invalidate cleared nothing");
      tokens.invalidate(newer);
      assertNotEquals(newer, tokens.token());
      assertEquals(3, server.hits("/oauth/token"));
    }
  }

  /**
   * 29.9.26r AC6: interrupting the waiter that started an exchange throws CancellationException
   * with its flag restored, while the flight completes and its token is cached.
   */
  @Test
  void anInterruptedWaiterLeavesTheFlightRunning() throws Exception {
    try (Fakes.Server server = new Fakes.Server().on("/oauth/token", numbered(3600, 500))) {
      ClientCredentialsTokenSource tokens = source(server);
      AtomicBoolean cancelled = new AtomicBoolean();
      AtomicBoolean flagged = new AtomicBoolean();
      Thread waiter =
          new Thread(
              () -> {
                try {
                  tokens.token();
                } catch (CancellationException e) {
                  cancelled.set(true);
                  flagged.set(Thread.currentThread().isInterrupted());
                }
              });
      waiter.start();
      Fakes.sleep(100);
      waiter.interrupt();
      waiter.join();
      assertTrue(cancelled.get());
      assertTrue(flagged.get());
      assertEquals(new AccessToken("lgr_at_1"), tokens.token());
      assertEquals(1, server.hits("/oauth/token"));
    }
  }

  /** Stalls after the headers (no body byte) or mid-body. */
  private static Fakes.Handler stall(boolean midBody) {
    return exchange -> {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, 100);
      if (midBody) {
        OutputStream out = exchange.getResponseBody();
        out.write("{\"access_to".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
      }
      Fakes.sleep(1500);
      throw new IOException("the stall is over");
    };
  }

  /**
   * 29.9.26r AC33: a token endpoint that stalls past tokenRequestTimeout, after its headers or
   * mid-body, fails that attempt with TIMEOUT; every waiter gets the same exception, nothing is
   * cached, and the next token() starts a fresh exchange.
   */
  @Test
  void tokenRequestTimeoutBoundsEachAttempt() throws Exception {
    for (boolean midBody : new boolean[] {false, true}) {
      try (Fakes.Server server = new Fakes.Server().on("/oauth/token", stall(midBody))) {
        ClientCredentialsTokenSource tokens = source(server, Duration.ofMillis(200));
        List<Object> got = together(2, tokens::token);
        TransportException first = assertInstanceOf(TransportException.class, got.get(0));
        assertEquals(TransportKind.TIMEOUT, first.kind());
        assertSame(first, got.get(1));
        assertThrows(TransportException.class, tokens::token);
        assertEquals(2, server.hits("/oauth/token"), "midBody=" + midBody);
      }
    }
  }
}
