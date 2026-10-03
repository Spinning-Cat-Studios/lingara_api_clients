package com.getlingara.client.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.LingaraException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookTest {
  private static JsonNode vectors() throws Exception {
    Path file = Path.of(System.getProperty("lingara.vectors"));
    return new ObjectMapper().readTree(file.toFile()).path("vectors");
  }

  private static Webhook webhook(JsonNode vector) {
    List<String> secrets = new ArrayList<>();
    vector.path("secrets").forEach(s -> secrets.add(s.asText()));
    Instant now = Instant.ofEpochSecond(vector.path("now").asLong());
    return Webhook.of(secrets, Clock.fixed(now, ZoneOffset.UTC));
  }

  private static Map<String, List<String>> headers(JsonNode vector) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    vector
        .path("headers")
        .properties()
        .forEach(e -> out.put(e.getKey(), List.of(e.getValue().asText())));
    return out;
  }

  private static byte[] body(JsonNode vector) {
    return vector.path("body").asText().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 30.9.26aa AC26: every shared vector in conformance/vectors/webhook-signatures.json gives its
   * expected result through {@code verify}: the event's id and type (an {@code UnknownEvent} where
   * the vector says so), the reason, or a refusal at construction. 30.9.26aa AC45: {@code
   * verifySignature} passes every {@code ok} and {@code malformed_payload} vector and raises every
   * other vector's own reason.
   */
  @Test
  void everySharedVectorVerifiesAsExpected() throws Exception {
    int checked = 0;
    for (JsonNode vector : vectors()) {
      String name = vector.path("name").asText();
      JsonNode expect = vector.path("expect");
      if (expect.path("refused").asBoolean()) {
        assertThrows(IllegalStateException.class, () -> webhook(vector), name);
      } else if (expect.has("ok")) {
        expectOk(name, vector, expect.path("ok"));
      } else {
        expectError(name, vector, expect.path("error").asText());
      }
      checked++;
    }
    assertEquals(28, checked);
  }

  private static void expectOk(String name, JsonNode vector, JsonNode ok) {
    Webhook webhook = webhook(vector);
    Event event = webhook.verify(body(vector), headers(vector));
    assertEquals(ok.path("id").asText(), event.id(), name);
    assertEquals(ok.path("type").asText(), event.type(), name);
    assertEquals(ok.path("unknown").asBoolean(), event instanceof UnknownEvent, name);
    webhook.verifySignature(body(vector), headers(vector));
  }

  private static void expectError(String name, JsonNode vector, String reason) {
    Webhook webhook = webhook(vector);
    WebhookVerificationException e =
        assertThrows(
            WebhookVerificationException.class,
            () -> webhook.verify(body(vector), headers(vector)),
            name);
    assertEquals(reason, e.reason().wireName(), name);
    if (reason.equals("malformed_payload")) {
      webhook.verifySignature(body(vector), headers(vector));
      return;
    }
    try {
      webhook.verifySignature(body(vector), headers(vector));
      fail(name + ": verifySignature passed");
    } catch (WebhookVerificationException s) {
      assertEquals(reason, s.reason().wireName(), name);
    }
  }

  /**
   * ADR 30.9.26aa D4: the error sits outside the sealed {@code LingaraException}, and neither it
   * nor the verifier renders a secret, a signature or the body.
   */
  @Test
  void theErrorIsOutsideTheRootAndNothingLeaks() throws Exception {
    assertFalse(LingaraException.class.isAssignableFrom(WebhookVerificationException.class));
    JsonNode vector = null;
    for (JsonNode v : vectors()) {
      if (v.path("name").asText().equals("body-not-json")) {
        vector = v;
      }
    }
    Webhook webhook = webhook(vector);
    JsonNode found = vector;
    WebhookVerificationException e =
        assertThrows(
            WebhookVerificationException.class, () -> webhook.verify(body(found), headers(found)));
    String secret = vector.path("secrets").get(0).asText();
    for (String rendering : List.of(String.valueOf(e), webhook.toString())) {
      assertFalse(rendering.contains(secret.substring(10)), rendering);
      assertFalse(rendering.contains("not json"), rendering);
      assertFalse(rendering.contains("v1,"), rendering);
    }
    assertTrue(e.getCause() == null);
  }
}
