package com.getlingara.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.client.canvas.CanvasFilters;
import com.getlingara.client.canvas.Canvases;
import com.getlingara.client.model.Canvas;
import com.getlingara.client.model.CanvasDraft;
import com.getlingara.client.model.CanvasSummary;
import com.getlingara.client.model.RenameCanvasRequest;
import com.getlingara.client.model.SaveCanvasDraftRequest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanvasTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DEFINITION =
      "{\"schema\":2,\"nodes\":[{\"id\":\"n1\",\"kind\":\"client\",\"position\":[0.5,1.5],"
          + "\"fields\":{\"name\":\"Lesson server\",\"x_editor_hint\":{\"collapsed\":true}}}]}";

  private static final String CANVAS =
      "{\"id\":\"c-1\",\"name\":\"Hooks\",\"created_at\":\"2026-10-20T09:00:00Z\","
          + "\"updated_at\":\"2026-10-20T09:00:00Z\",\"draft\":{\"revision\":1,"
          + "\"draft_version\":4,\"definition\":"
          + DEFINITION
          + ",\"objects\":{}},\"active\":null}";

  private static String page(String id, String next, boolean more) {
    String cursor = next == null ? "null" : "\"" + next + "\"";
    return "{\"items\":[{\"id\":\""
        + id
        + "\",\"name\":\"Hooks\",\"recipe\":\"blank\",\"created_at\":\"2026-10-20T09:00:00Z\","
        + "\"updated_at\":\"2026-10-20T09:00:00Z\",\"has_draft\":true,\"active_revision\":null,"
        + "\"node_count\":1,\"node_kinds\":[\"client\"],\"scopes\":[],\"objects\":[]}],"
        + "\"total\":null,\"next_cursor\":"
        + cursor
        + ",\"has_more\":"
        + more
        + "}";
  }

  /**
   * 16.10.26ak AC12: through {@code client.canvases()}, PUT and PATCH reach the server with their
   * JSON bodies and {@code Content-Type: application/json}; a definition read then saved keeps an
   * unknown {@code fields} key; and {@code pageCanvases} ends on {@code has_more: false} with its
   * filters resent, and throws {@code malformed_response} on a page with {@code has_more} but no
   * cursor.
   */
  @Test
  void putPatchAndPaging() throws Exception {
    List<String> queries = new ArrayList<>();
    try (Fakes.Server server = server(queries)) {
      Canvases canvases =
          LingaraClient.builder()
              .baseUrl(server.uri())
              .tokenUrl(server.uri("/oauth/token"))
              .clientCredentials("lgr_cid_test", "lgr_cs_test")
              .build()
              .canvases();

      Canvas read = canvases.getCanvas("c-1").body();
      CanvasDraft draft = read.getDraft();
      canvases.saveCanvasDraft(
          "c-1",
          new SaveCanvasDraftRequest()
              .definition(draft.getDefinition())
              .draftVersion(draft.getDraftVersion()));
      canvases.renameCanvas("c-1", new RenameCanvasRequest().name("Renamed"));
      List<String> ids = new ArrayList<>();
      for (CanvasSummary item :
          canvases.pageCanvases(CanvasFilters.of().limit(1).kinds("client", "webhook"))) {
        ids.add(item.getId());
      }
      assertEquals(List.of("c-1", "c-2"), ids);
      Iterator<CanvasSummary> broken = canvases.pageCanvases(CanvasFilters.of()).iterator();
      TransportException malformed = assertThrows(TransportException.class, broken::hasNext);
      assertEquals(TransportKind.MALFORMED_RESPONSE, malformed.kind());
      assertSent(server.seen.stream().filter(s -> s.path().startsWith("/v1/")).toList());
      assertEquals(
          List.of("limit=1&kinds=client%2Cwebhook", "cursor=cv1&limit=1&kinds=client%2Cwebhook"),
          queries.subList(0, 2));
      assertEquals(3, queries.size());
    }
  }

  /** Canvases on {@code /v1/canvases/…}; the list's three pages, each query recorded. */
  private static Fakes.Server server(List<String> queries) throws java.io.IOException {
    Fakes.Handler pages =
        Fakes.script(
            e -> Fakes.json(e, 200, page("c-1", "cv1", true)),
            e -> Fakes.json(e, 200, page("c-2", null, false)),
            e -> Fakes.json(e, 200, page("c-3", null, true)));
    return new Fakes.Server()
        .on("/oauth/token", Fakes.token("lgr_at_canvas", 3600))
        .on("/v1/canvases/", e -> Fakes.json(e, 200, CANVAS))
        .on(
            "/v1/canvases",
            e -> {
              queries.add(e.getRequestURI().getRawQuery());
              pages.handle(e);
            });
  }

  /** The PUT and the PATCH: method, path, JSON body and content type. */
  private static void assertSent(List<Fakes.Seen> sent) throws Exception {
    assertEquals(
        List.of("PUT", "/v1/canvases/c-1/draft"),
        List.of(sent.get(1).method(), sent.get(1).path()));
    JsonNode saved = JSON.readTree(sent.get(1).body());
    assertEquals(JSON.readTree(DEFINITION), saved.get("definition"));
    assertEquals(4, saved.get("draft_version").asInt());
    assertEquals(
        List.of("PATCH", "/v1/canvases/c-1"), List.of(sent.get(2).method(), sent.get(2).path()));
    assertEquals(JSON.readTree("{\"name\":\"Renamed\"}"), JSON.readTree(sent.get(2).body()));
    for (Fakes.Seen putOrPatch : sent.subList(1, 3)) {
      assertEquals("application/json", putOrPatch.header("content-type"));
    }
  }
}
