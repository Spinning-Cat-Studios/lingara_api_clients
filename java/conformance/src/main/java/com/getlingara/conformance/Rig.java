package com.getlingara.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.DeprecationNotice;
import com.getlingara.client.LingaraClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One case's client, built from its {@code client} block through the builder only, with a virtual
 * clock, a recording sleeper and, when the case asks, a recording deprecation hook.
 */
final class Rig {
  /** Where the virtual clock starts for every case (README, Comparison rules). */
  static final long CLOCK_START = 1_790_000_000L;

  final AtomicLong now = new AtomicLong(CLOCK_START);
  private final List<Duration> sleeps = new ArrayList<>();
  private final List<JsonNode> hooks = new ArrayList<>();
  LingaraClient client;

  private Rig() {}

  static Rig build(JsonNode block, String base, String token) {
    Rig rig = new Rig();
    boolean unreachable = "unreachable".equals(block.path("base_url").asText());
    String baseUrl = unreachable ? closedPort() : base;
    LingaraClient.Builder builder =
        LingaraClient.builder()
            .baseUrl(URI.create(baseUrl))
            .tokenUrl(URI.create(unreachable ? baseUrl + "/oauth/token" : token))
            .clock(rig.new VirtualClock())
            .sleeper(rig::recordSleep);
    Options.apply(block, builder);
    if ("record".equals(block.path("deprecation_hook").asText())) {
      builder.onDeprecation(rig::recordHook);
    }
    rig.client = builder.build();
    return rig;
  }

  void advance(long seconds) {
    now.addAndGet(seconds);
  }

  /** Clears what one step records, before it runs. */
  synchronized void reset() {
    sleeps.clear();
    hooks.clear();
  }

  synchronized List<Long> sleepsSeconds() {
    List<Long> out = new ArrayList<>();
    sleeps.forEach(d -> out.add(Math.round(d.toMillis() / 1000.0)));
    return out;
  }

  synchronized List<JsonNode> hookCalls() {
    return new ArrayList<>(hooks);
  }

  private synchronized void recordSleep(Duration duration) {
    sleeps.add(duration);
  }

  private synchronized void recordHook(DeprecationNotice notice) {
    ObjectNode call = Harness.JSON.createObjectNode();
    call.put("version", notice.version().orElse(null));
    call.put("deprecated_at", notice.deprecatedAt().map(Instant::getEpochSecond).orElse(null));
    call.put("sunset_at", notice.sunsetAt().map(Instant::getEpochSecond).orElse(null));
    notice
        .link()
        .ifPresent(
            link -> {
              ObjectNode l = call.putObject("link");
              l.put("raw", link.raw());
              l.put("target", link.target().map(URI::toString).orElse(null));
            });
    hooks.add(call);
  }

  /** A port bound and released, so nothing listens on it. */
  private static String closedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return "http://127.0.0.1:" + socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Advances only when a case says so. */
  private final class VirtualClock extends Clock {
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
      return Instant.ofEpochSecond(now.get());
    }
  }

  /** The rest of the client block, onto the public builder. */
  private static final class Options {
    private Options() {}

    static void apply(JsonNode c, LingaraClient.Builder builder) {
      JsonNode credentials = c.path("credentials");
      if (credentials.isObject()) {
        builder.clientCredentials(
            credentials.path("client_id").asText(), credentials.path("client_secret").asText());
        if ("post".equals(credentials.path("auth").asText())) {
          builder.clientSecretPost();
        }
      }
      if (c.path("scopes").isArray()) {
        List<String> scopes = new ArrayList<>();
        c.path("scopes").forEach(s -> scopes.add(s.asText()));
        builder.scopes(scopes.toArray(String[]::new));
      }
      if (c.path("version").isTextual()) {
        builder.version(c.path("version").asText());
      }
      retries(c.path("retries"), builder);
      if (c.path("user_agent_suffix").isTextual()) {
        builder.userAgentSuffix(c.path("user_agent_suffix").asText());
      }
      if (c.path("stream_idle_timeout_ms").isNumber()) {
        builder.streamIdleTimeout(Duration.ofMillis(c.path("stream_idle_timeout_ms").asLong()));
      }
    }

    private static void retries(JsonNode retries, LingaraClient.Builder builder) {
      if (retries.path("max_attempts").isNumber()) {
        builder.maxAttempts(retries.path("max_attempts").asInt());
      }
      if (retries.path("retry_after_cap_s").isNumber()) {
        builder.retryAfterCap(Duration.ofSeconds(retries.path("retry_after_cap_s").asLong()));
      }
    }
  }
}
