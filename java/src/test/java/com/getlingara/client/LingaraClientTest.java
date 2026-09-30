package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LingaraClientTest {
  /** CONTRACT.md K6's pattern, which the conformance server checks on every request. */
  private static final Pattern K6 =
      Pattern.compile(
          "^lingara-(typescript|rust|go|java|kotlin|ruby|php)/(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\."
              + "(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)? \\([\\x20-\\x28\\x2A-\\x7E]+\\)( .+)?$");

  private static final String VERSIONS = "{\"current\":null,\"versions\":[]}";
  private static final String PIN = "2026-09-knowing-tenpounder";

  private static Fakes.Server server() throws java.io.IOException {
    return new Fakes.Server()
        .on("/oauth/token", Fakes.token("lgr_at_client", 3600))
        .on("/v1/usage", Fakes.status(200, "Lingara-Version", PIN, "{\"allowance\":[]}"))
        .on("/v1/versions", Fakes.status(200, "Lingara-Version", PIN, VERSIONS));
  }

  private static LingaraClient.Builder credentialed(Fakes.Server server) {
    return LingaraClient.builder()
        .baseUrl(server.uri())
        .tokenUrl(server.uri("/oauth/token"))
        .clientCredentials("lgr_cid_test", "lgr_cs_test");
  }

  /**
   * 29.9.26r AC18: the User-Agent matches C2 D8's pattern with lang java and a jvm/ runtime with no
   * ")", and a suffix is appended after it.
   */
  @Test
  void userAgentLeadsWithTheLibraryToken() throws Exception {
    try (Fakes.Server server = server()) {
      LingaraClient.builder().baseUrl(server.uri()).build().listApiVersions();
      LingaraClient.builder()
          .baseUrl(server.uri())
          .userAgentSuffix("kanji-quest/2.1")
          .build()
          .listApiVersions();
      String plain = server.seen.get(0).header("user-agent");
      String suffixed = server.seen.get(1).header("user-agent");
      assertTrue(K6.matcher(plain).matches(), plain);
      assertTrue(
          plain.startsWith("lingara-java/" + LingaraClient.LIBRARY_VERSION + " (jvm/"), plain);
      assertTrue(K6.matcher(suffixed).matches(), suffixed);
      assertEquals(plain + " kanji-quest/2.1", suffixed);
    }
  }

  /**
   * 29.9.26r AC19: a pinned client sends Lingara-Version on every /v1 request and never to the
   * token endpoint, and User-Agent goes to both.
   */
  @Test
  void headersReachTheRightEndpoints() throws Exception {
    try (Fakes.Server server = server()) {
      LingaraClient client = credentialed(server).version(PIN).build();
      client.getUsage();
      client.listApiVersions();
      for (Fakes.Seen seen : server.seen) {
        assertTrue(seen.header("user-agent").startsWith("lingara-java/"), seen.path());
        if (seen.path().equals("/oauth/token")) {
          assertNull(seen.header("lingara-version"));
        } else {
          assertEquals(PIN, seen.header("lingara-version"), seen.path());
        }
      }
      assertEquals(3, server.seen.size());
    }
  }

  /**
   * 29.9.26r AC20: a JSON method's ApiResponse exposes servedVersion() from the echo, and a
   * credential-free client calls listApiVersions and getApiVersion(id) with no exchange and no
   * Authorization header.
   */
  @Test
  void servedVersionAndTheCredentialFreeClient() throws Exception {
    try (Fakes.Server server =
        server().on("/v1/versions/", Fakes.status(200, null, null, "{\"id\":\"" + PIN + "\"}"))) {
      LingaraClient free = LingaraClient.builder().baseUrl(server.uri()).build();
      assertEquals(Optional.of(PIN), free.listApiVersions().servedVersion());
      assertEquals(PIN, free.getApiVersion(PIN).body().getId());
      assertEquals(Optional.empty(), free.getApiVersion(PIN).servedVersion());
      credentialed(server).build().listApiVersions();
      assertEquals(0, server.hits("/oauth/token"));
      server.seen.forEach(seen -> assertNull(seen.header("authorization"), seen.path()));
      assertEquals("/v1/versions/" + PIN, server.seen.get(1).path());
    }
  }

  /**
   * 29.9.26r AC21: build() throws IllegalStateException for an empty version and for tokenSource
   * beside clientCredentials.
   */
  @Test
  void buildRefusesConflictingOptions() {
    assertThrows(IllegalStateException.class, () -> LingaraClient.builder().version("").build());
    TokenSource own =
        new TokenSource() {
          @Override
          public AccessToken token() {
            return new AccessToken("lgr_at_own");
          }

          @Override
          public void invalidate(AccessToken token) {}
        };
    assertThrows(
        IllegalStateException.class,
        () -> LingaraClient.builder().clientCredentials("id", "secret").tokenSource(own).build());
    LingaraClient.builder().tokenSource(own).build();
  }
}
