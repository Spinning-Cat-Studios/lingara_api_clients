package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.dialogue.DialogueFilters;
import com.getlingara.client.dialogue.Dialogues;
import com.getlingara.client.dialogue.ExportDialogueOptions;
import com.getlingara.client.dialogue.ExportedDialogue;
import com.getlingara.client.model.Dialogue;
import com.getlingara.client.model.DialogueDraft;
import com.getlingara.client.model.DialogueSummary;
import com.getlingara.client.model.RenameDialogueRequest;
import com.getlingara.client.model.SaveDialogueDraftRequest;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogueTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DEFINITION =
      "{\"schema\":\"dialogue.v1\",\"id\":\"keepers\",\"start\":\"greet\",\"checkpoints\":[],"
          + "\"facts\":[],\"x-layout\":{\"greet\":{\"x\":120,\"y\":40}}}";

  /** Canonical bytes in an order no JSON writer would choose, so a re-encode would show. */
  private static final String IR = "{\"start\":\"greet\",\"id\":\"keepers\",\"facts\":[ ]}";

  private static final String ETAG = "\"3b7e\"";

  private static String dialogue(String warningCode) {
    return "{\"id\":\"d-1\",\"name\":\"Night market\",\"created_at\":\"2026-10-28T09:00:00Z\","
        + "\"updated_at\":\"2026-10-28T09:00:00Z\",\"draft\":{\"revision\":1,"
        + "\"draft_version\":4,\"definition\":"
        + DEFINITION
        + "},\"active\":null,\"warnings\":[{\"code\":\""
        + warningCode
        + "\",\"path\":\"/checkpoints\"}]}";
  }

  private static String page(String id, String next, boolean more) {
    String cursor = next == null ? "null" : "\"" + next + "\"";
    return "{\"items\":[{\"id\":\""
        + id
        + "\",\"name\":\"Night market\",\"ir_id\":\"keepers\",\"created_at\":"
        + "\"2026-10-28T09:00:00Z\",\"updated_at\":\"2026-10-28T09:00:00Z\",\"has_draft\":true,"
        + "\"active_revision\":null,\"npc_count\":1,\"checkpoint_count\":2}],\"next_cursor\":"
        + cursor
        + ",\"has_more\":"
        + more
        + "}";
  }

  /**
   * 29.10.26c AC19: through {@code client.dialogues()}, PUT and PATCH reach the server with their
   * JSON bodies and {@code Content-Type: application/json}; the export answers {@code Body} with
   * the bytes unparsed and both headers, and {@code NotModified} on a {@code 304}; and {@code
   * pageDialogues} ends on {@code has_more: false} with its filters resent.
   */
  @Test
  void putPatchExportAndPaging() throws Exception {
    List<String> queries = new ArrayList<>();
    try (Fakes.Server server = server(queries, "checkpoint_unreachable")) {
      Dialogues dialogues = client(server).dialogues();

      DialogueDraft draft = dialogues.getDialogue("d-1").body().getDraft();
      dialogues.saveDialogueDraft(
          "d-1",
          new SaveDialogueDraftRequest()
              .definition(draft.getDefinition())
              .draftVersion(draft.getDraftVersion()));
      dialogues.renameDialogue("d-1", new RenameDialogueRequest().name("Renamed"));
      assertSent(server.seen.stream().filter(s -> s.path().startsWith("/v1/")).toList());

      ExportedDialogue body = dialogues.exportDialogue("d-1", ExportDialogueOptions.of()).body();
      assertEquals(new ExportedDialogue.Body(3, ETAG, IR), body);
      ExportedDialogue same =
          dialogues.exportDialogue("d-1", ExportDialogueOptions.of().ifNoneMatch(ETAG)).body();
      assertEquals(new ExportedDialogue.NotModified(3, ETAG), same);
      List<Fakes.Seen> exports =
          server.seen.stream().filter(s -> s.path().endsWith("/export")).toList();
      assertEquals(null, exports.get(0).header("if-none-match"));
      assertEquals(ETAG, exports.get(1).header("if-none-match"));

      List<String> ids = new ArrayList<>();
      for (DialogueSummary item :
          dialogues.pageDialogues(DialogueFilters.of().limit(1).name("night market"))) {
        ids.add(item.getId());
      }
      assertEquals(List.of("d-1", "d-2"), ids);
      assertEquals(
          List.of("limit=1&name=night%20market", "cursor=dl1&limit=1&name=night%20market"),
          queries);
    }
  }

  /**
   * 29.10.26c D4, Step 0: the generated {@code DialogueWarningCode} is a closed enum, so a warning
   * code a later spec adds fails the whole {@code Dialogue}'s decode until the library is re-synced
   * (the README says so).
   */
  @Test
  void anUnknownWarningCodeFailsTheDecode() throws Exception {
    try (Fakes.Server server = server(new ArrayList<>(), "a_code_from_tomorrow")) {
      Dialogues dialogues = client(server).dialogues();
      TransportException e =
          assertThrows(TransportException.class, () -> dialogues.getDialogue("d-1"));
      assertEquals(TransportKind.MALFORMED_RESPONSE, e.kind());
      Dialogue known = JSON.readValue(dialogue("outcome_unreachable"), Dialogue.class);
      assertEquals("outcome_unreachable", known.getWarnings().get(0).getCode().getValue());
    }
  }

  private static LingaraClient client(Fakes.Server server) {
    return LingaraClient.builder()
        .baseUrl(server.uri())
        .tokenUrl(server.uri("/oauth/token"))
        .clientCredentials("lgr_cid_test", "lgr_cs_test")
        .build();
  }

  /** Dialogues on {@code /v1/dialogues/…}; the export's two answers; the list's two pages. */
  private static Fakes.Server server(List<String> queries, String warningCode) throws IOException {
    Fakes.Handler pages =
        Fakes.script(
            e -> Fakes.json(e, 200, page("d-1", "dl1", true)),
            e -> Fakes.json(e, 200, page("d-2", null, false)));
    Fakes.Handler exports = Fakes.script(e -> exported(e, 200, IR), e -> exported(e, 304, ""));
    return new Fakes.Server()
        .on("/oauth/token", Fakes.token("lgr_at_dialogue", 3600))
        .on(
            "/v1/dialogues/",
            e -> {
              if (e.getRequestURI().getPath().endsWith("/export")) {
                exports.handle(e);
              } else {
                Fakes.json(e, 200, dialogue(warningCode));
              }
            })
        .on(
            "/v1/dialogues",
            e -> {
              queries.add(e.getRequestURI().getRawQuery());
              pages.handle(e);
            });
  }

  private static void exported(HttpExchange e, int status, String body) throws IOException {
    e.getResponseHeaders().set("ETag", ETAG);
    e.getResponseHeaders().set("Lingara-Dialogue-Revision", "3");
    Fakes.respond(e, status, status == 304 ? null : "application/json", body);
  }

  /** The PUT and the PATCH: method, path, JSON body and content type. */
  private static void assertSent(List<Fakes.Seen> sent) throws Exception {
    assertEquals(
        List.of("PUT", "/v1/dialogues/d-1/draft"),
        List.of(sent.get(1).method(), sent.get(1).path()));
    JsonNode saved = JSON.readTree(sent.get(1).body());
    assertEquals(JSON.readTree(DEFINITION), saved.get("definition"));
    assertEquals(4, saved.get("draft_version").asInt());
    assertEquals(
        List.of("PATCH", "/v1/dialogues/d-1"), List.of(sent.get(2).method(), sent.get(2).path()));
    assertEquals(JSON.readTree("{\"name\":\"Renamed\"}"), JSON.readTree(sent.get(2).body()));
    for (Fakes.Seen putOrPatch : sent.subList(1, 3)) {
      assertEquals("application/json", putOrPatch.header("content-type"));
    }
  }
}
