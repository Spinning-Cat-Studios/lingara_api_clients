package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.getlingara.client.internal.ErrorMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
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
