package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.internal.ErrorMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedactionTest {
  private static final String SECRET = "lgr_cs_redact_0123456789abcdefghijklmnopqrstu";
  private static final String TOKEN = "lgr_at_redact_token";

  /** Every rendering of a throwable and its causes. */
  private static List<String> renderings(Throwable failure) {
    List<String> out = new ArrayList<>();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      out.add(String.valueOf(t));
      out.add(String.valueOf(t.getMessage()));
    }
    return out;
  }

  private static void assertRedacted(List<String> renderings) {
    for (String r : renderings) {
      assertFalse(r.contains(SECRET), r);
      assertFalse(r.contains(TOKEN), r);
    }
  }

  /**
   * 29.9.26r AC9: neither the secret nor the token appears in the toString() of ClientSecret,
   * AccessToken, ClientCredentialsTokenSource, LingaraClient or any exception, nor in any
   * exception's getMessage() or cause, and each rendering that would hold one shows [REDACTED];
   * exposeSecret() is the only public accessor that returns a raw value.
   */
  @Test
  void secretsNeverRender() throws Exception {
    List<String> renderings = new ArrayList<>();
    try (Fakes.Server server =
        new Fakes.Server()
            .on("/oauth/token", Fakes.token(TOKEN, 3600))
            .on(
                "/v1/usage",
                Fakes.status(403, null, null, "{\"code\":\"forbidden\",\"error\":\"No.\"}"))) {
      LingaraClient client =
          LingaraClient.builder()
              .baseUrl(server.uri())
              .tokenUrl(server.uri("/oauth/token"))
              .clientCredentials("lgr_cid_redact", SECRET)
              .build();
      renderings.addAll(renderings(assertThrows(ApiException.class, client::getUsage)));
      renderings.add(client.toString());
      assertTrue(client.toString().contains("lgr_cid_redact"), "the client id is rendered");
      assertTrue(client.toString().contains("[REDACTED]"));
    }
    try (Fakes.Server server =
        new Fakes.Server()
            .on("/oauth/token", Fakes.status(401, null, null, "{\"error\":\"invalid_client\"}"))) {
      LingaraClient client =
          LingaraClient.builder()
              .baseUrl(server.uri())
              .tokenUrl(server.uri("/oauth/token"))
              .clientCredentials("lgr_cid_redact", SECRET)
              .build();
      renderings.addAll(renderings(assertThrows(OAuthException.class, client::getUsage)));
    }
    TransportException echoed =
        ErrorMapper.transport(
            new IOException("the proxy echoed Basic " + SECRET + " and Bearer " + TOKEN),
            false,
            List.of(SECRET, TOKEN));
    renderings.addAll(renderings(echoed));
    assertEquals(TransportKind.CONNECT, echoed.kind(), "scrubbing keeps the kind");
    assertEquals("[REDACTED]", new ClientSecret(SECRET).toString());
    assertEquals("[REDACTED]", new AccessToken(TOKEN).toString());
    assertRedacted(renderings);
    assertOnlyExposeSecretIsRaw(new ClientSecret(SECRET), SECRET);
    assertOnlyExposeSecretIsRaw(new AccessToken(TOKEN), TOKEN);
  }

  /**
   * 1.10.26w AC17: MintedToken.toString() redacts and token().exposeSecret() returns the value,
   * while the other five fields read as sent; a fixture missing subject is refused as
   * malformed_response, and that error renders no token.
   */
  @Test
  void aMintedTokenRendersRedacted() throws Exception {
    String minted = "lgr_et_redact_0123456789abcdefghijklmnopqrstuvwxyz0";
    ObjectMapper mapper = LingaraClient.mapper();
    ObjectNode answer = mapper.createObjectNode();
    answer.put("token", minted).put("expires_at", "2026-10-01T09:27:44Z").put("expires_in", 900);
    answer.put("subject", "lgr_sub_redact").put("account_linked", false);
    answer.putArray("scopes").add("embed:play");
    MintedToken token = MintedToken.of(answer, mapper);
    assertFalse(token.toString().contains(minted), token.toString());
    assertTrue(token.toString().contains("[REDACTED]"));
    ApiResponse<MintedToken> wrapped = new ApiResponse<>(token, Optional.empty());
    assertFalse(wrapped.toString().contains(minted), wrapped.toString());
    assertEquals(minted, token.token().exposeSecret());
    assertEquals("2026-10-01T09:27:44Z", token.expiresAt());
    assertEquals(Duration.ofSeconds(900), token.expiresIn());
    assertEquals("lgr_sub_redact", token.subject());
    assertEquals(List.of("embed:play"), token.scopes());
    assertFalse(token.accountLinked());

    answer.remove("subject");
    TransportException refused =
        assertThrows(TransportException.class, () -> MintedToken.of(answer, mapper));
    assertEquals(TransportKind.MALFORMED_RESPONSE, refused.kind());
    renderings(refused).forEach(r -> assertFalse(r.contains(minted), r));
    answer.put("subject", "lgr_sub_redact").put("token", "lgr_at_not_an_embed_token");
    assertThrows(TransportException.class, () -> MintedToken.of(answer, mapper));
  }

  private static void assertOnlyExposeSecretIsRaw(Object holder, String raw) throws Exception {
    for (Method m : holder.getClass().getMethods()) {
      boolean accessor =
          m.getParameterCount() == 0
              && !Modifier.isStatic(m.getModifiers())
              && m.getReturnType() == String.class;
      if (accessor && raw.equals(m.invoke(holder))) {
        assertEquals("exposeSecret", m.getName());
      }
    }
  }
}
