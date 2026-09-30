package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class DeprecationTest {
  private static final String LINK = "</v1/versions/2026-09-affable-cat>; rel=\"deprecation\"";

  /** A /v1/versions answering under {@code version}, deprecated when {@code deprecation} is set. */
  private static Fakes.Handler versions(String version, String deprecation, String sunset) {
    return exchange -> {
      var headers = exchange.getResponseHeaders();
      headers.set("Lingara-Version", version);
      if (deprecation != null) {
        headers.set("Deprecation", deprecation);
        headers.set("Sunset", sunset);
        headers.set("Link", LINK);
      }
      Fakes.json(exchange, 200, "{\"versions\":[]}");
    };
  }

  /** Captures the library's WARNING records. */
  private static final class Warnings extends Handler {
    final List<String> messages = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void publish(LogRecord record) {
      if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
        messages.add(record.getMessage());
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    long deprecated() {
      return messages.stream().filter(m -> m.contains("is deprecated")).count();
    }
  }

  /**
   * 29.9.26r AC22: a Deprecation header calls the hook once with parsed instants and a Link
   * resolved against the request URI; a response without one does not call it; an unparseable
   * header leaves its field empty; a throwing hook does not fail the call; with no hook one warning
   * is logged per version id.
   */
  @Test
  void deprecationHookParsingAndWarnOnce() throws Exception {
    List<DeprecationNotice> calls = Collections.synchronizedList(new ArrayList<>());
    try (Fakes.Server server =
        new Fakes.Server()
            .on(
                "/v1/versions",
                Fakes.script(
                    versions("2026-09-affable-cat", "@1790812800", "Mon, 01 Mar 2027 00:00:00 GMT"),
                    versions("2026-09-affable-cat", null, null),
                    versions("2026-09-affable-cat", "yesterday", "soon")))) {
      LingaraClient client =
          LingaraClient.builder().baseUrl(server.uri()).onDeprecation(calls::add).build();
      client.listApiVersions();
      DeprecationNotice notice = calls.get(0);
      assertEquals(Optional.of("2026-09-affable-cat"), notice.version());
      assertEquals(Optional.of(Instant.ofEpochSecond(1790812800)), notice.deprecatedAt());
      assertEquals(Optional.of(Instant.ofEpochSecond(1803859200)), notice.sunsetAt());
      assertEquals(LINK, notice.link().orElseThrow().raw());
      assertEquals(
          Optional.of(URI.create(server.uri() + "/v1/versions/2026-09-affable-cat")),
          notice.link().orElseThrow().target());
      client.listApiVersions();
      assertEquals(1, calls.size(), "no Deprecation, no call");
      client.listApiVersions();
      DeprecationNotice raw = calls.get(1);
      assertEquals(Optional.empty(), raw.deprecatedAt());
      assertEquals(Optional.empty(), raw.sunsetAt());
      assertEquals("yesterday", raw.deprecation());
      assertEquals(Optional.of("soon"), raw.sunset());
    }
    assertThrowingHookAndWarnOnce();
  }

  private static void assertThrowingHookAndWarnOnce() throws Exception {
    Logger logger = Logger.getLogger("com.getlingara.client");
    Warnings warnings = new Warnings();
    logger.addHandler(warnings);
    try (Fakes.Server server =
        new Fakes.Server()
            .on(
                "/v1/versions",
                Fakes.script(
                    versions("2026-09-affable-cat", "@1", "x"),
                    versions("2026-09-affable-cat", "@1", "x"),
                    versions("2026-09-affable-cat", "@1", "x"),
                    versions("2026-09-brave-otter", "@1", "x")))) {
      LingaraClient throwing =
          LingaraClient.builder()
              .baseUrl(server.uri())
              .onDeprecation(
                  n -> {
                    throw new IllegalStateException("a caller's bug");
                  })
              .build();
      throwing.listApiVersions();
      assertEquals(0, warnings.deprecated(), "a hook replaces the warning");
      LingaraClient quiet = LingaraClient.builder().baseUrl(server.uri()).build();
      quiet.listApiVersions();
      quiet.listApiVersions();
      assertEquals(1, warnings.deprecated(), "one warning per version id");
      assertTrue(warnings.messages.stream().anyMatch(m -> m.contains("2026-09-affable-cat")));
      quiet.listApiVersions();
      assertEquals(2, warnings.deprecated(), "a second id warns again");
    } finally {
      logger.removeHandler(warnings);
    }
  }
}
