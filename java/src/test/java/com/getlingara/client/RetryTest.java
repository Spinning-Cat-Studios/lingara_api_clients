package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RetryTest {
  private static final String OK = "{\"allowance\":[]}";
  private static final String LIMITED = "{\"code\":\"rate_limited\",\"error\":\"Slow down.\"}";

  private final Fakes.SettableClock clock = new Fakes.SettableClock();
  private final Fakes.RecordingSleeper sleeper = new Fakes.RecordingSleeper();

  private LingaraClient client(Fakes.Server server) {
    return LingaraClient.builder().baseUrl(server.uri()).clock(clock).sleeper(sleeper).build();
  }

  private static Fakes.Server usage(Fakes.Handler... script) throws java.io.IOException {
    return new Fakes.Server().on("/v1/usage", Fakes.script(script));
  }

  /**
   * 29.9.26r AC7: a Retry-After above retryAfterCap throws at once with retryAfter set; a missing
   * one throws at once; an HTTP-date is read against the Clock; three 429s throw after two sleeps.
   */
  @Test
  void retryAfterCapMissingHeaderDateAndExhaustion() throws Exception {
    try (Fakes.Server server = usage(Fakes.status(429, "Retry-After", "120", LIMITED))) {
      ApiException e = assertThrows(ApiException.class, client(server)::getUsage);
      assertEquals(Optional.of(Duration.ofSeconds(120)), e.retryAfter());
      assertEquals(1, server.hits("/v1/usage"));
    }
    try (Fakes.Server server = usage(Fakes.status(429, null, null, LIMITED))) {
      ApiException e = assertThrows(ApiException.class, client(server)::getUsage);
      assertEquals(Optional.empty(), e.retryAfter());
      assertEquals(1, server.hits("/v1/usage"));
    }
    assertEquals(List.of(), sleeper.sleeps);
    String inSeven =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            clock.instant().plusSeconds(7).atOffset(ZoneOffset.UTC));
    try (Fakes.Server server =
        usage(
            Fakes.status(503, "Retry-After", inSeven, LIMITED),
            Fakes.status(200, null, null, OK))) {
      client(server).getUsage();
      assertEquals(List.of(Duration.ofSeconds(7)), sleeper.sleeps);
    }
    sleeper.sleeps.clear();
    try (Fakes.Server server = usage(Fakes.status(429, "Retry-After", "1", LIMITED))) {
      assertThrows(ApiException.class, client(server)::getUsage);
      assertEquals(3, server.hits("/v1/usage"));
      assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(1)), sleeper.sleeps);
    }
  }

  /**
   * 29.9.26r AC8: an interrupt during a Retry-After wait restores the flag and throws
   * CancellationException, not a LingaraException, and no further request is made.
   */
  @Test
  void interruptDuringBackoffIsCancellation() throws Exception {
    try (Fakes.Server server = usage(Fakes.status(429, "Retry-After", "30", LIMITED))) {
      LingaraClient client = LingaraClient.builder().baseUrl(server.uri()).build();
      AtomicReference<Throwable> thrown = new AtomicReference<>();
      AtomicReference<Boolean> flagged = new AtomicReference<>();
      Thread caller =
          new Thread(
              () -> {
                try {
                  client.getUsage();
                } catch (RuntimeException e) {
                  thrown.set(e);
                  flagged.set(Thread.currentThread().isInterrupted());
                }
              });
      caller.start();
      Fakes.sleep(300);
      caller.interrupt();
      caller.join(5000);
      assertTrue(thrown.get() instanceof CancellationException, String.valueOf(thrown.get()));
      assertTrue(flagged.get());
      Fakes.sleep(100);
      assertEquals(1, server.hits("/v1/usage"));
    }
  }
}
