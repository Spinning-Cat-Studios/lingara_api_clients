package com.getlingara.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.client.ApiResponse;
import com.getlingara.client.LingaraClient;
import com.getlingara.client.dialogue.DialogueFilters;
import com.getlingara.client.dialogue.Dialogues;
import com.getlingara.client.dialogue.ExportDialogueOptions;
import com.getlingara.client.dialogue.ExportedDialogue;
import com.getlingara.client.dialogue.ListDialoguesParams;
import com.getlingara.client.model.ActivateDialogueRequest;
import com.getlingara.client.model.CreateDialogueRequest;
import com.getlingara.client.model.DialogueSummary;
import com.getlingara.client.model.RenameDialogueRequest;
import com.getlingara.client.model.SaveDialogueDraftRequest;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The harness's dialogue pieces (ADR 29.10.26c D7): the seven operations on {@code
 * client.dialogues()}, the export's reported object, and the {@code page_dialogues} step.
 */
final class DialogueSteps {
  private DialogueSteps() {}

  /** One of the seven dialogue operations, its id from {@code params.dialogue_id}; null if not. */
  static Observe.Seen call(LingaraClient c, JsonNode call) {
    Dialogues dialogues = c.dialogues();
    JsonNode params = call.path("params");
    String id = params.path("dialogue_id").asText(null);
    JsonNode body = call.path("body");
    String operation = call.path("operation").asText();
    if (operation.equals("exportDialogue")) {
      return export(dialogues, id, params);
    }
    Supplier<ApiResponse<?>> run =
        switch (operation) {
          case "listDialogues" ->
              () ->
                  dialogues.listDialogues(
                      new ListDialoguesParams(params.path("cursor").asText(null), filters(params)));
          case "createDialogue" ->
              () -> dialogues.createDialogue(as(body, CreateDialogueRequest.class));
          case "getDialogue" -> () -> dialogues.getDialogue(id);
          case "renameDialogue" ->
              () -> dialogues.renameDialogue(id, as(body, RenameDialogueRequest.class));
          case "saveDialogueDraft" ->
              () -> dialogues.saveDialogueDraft(id, as(body, SaveDialogueDraftRequest.class));
          case "activateDialogue" ->
              () -> dialogues.activateDialogue(id, as(body, ActivateDialogueRequest.class));
          default -> null;
        };
    return run == null ? null : Observe.result(run);
  }

  /**
   * An export, reported as D7's object: {@code {revision, etag, ir_json}} with status 200, or
   * {@code {not_modified: true, revision, etag}} with status 304.
   */
  private static Observe.Seen export(Dialogues dialogues, String id, JsonNode params) {
    ExportDialogueOptions options =
        new ExportDialogueOptions(
            params.path("revision").asText(null),
            params.path("layout").asText(null),
            params.path("if_none_match").asText(null));
    ApiResponse<ExportedDialogue> response;
    try {
      response = dialogues.exportDialogue(id, options);
    } catch (RuntimeException e) {
      return Observe.failed(e, List.of(), Optional.empty());
    }
    ObjectNode out = Harness.JSON.createObjectNode();
    Observe.Seen seen = new Observe.Seen();
    seen.outcome = "completed";
    if (response.body() instanceof ExportedDialogue.Body b) {
      seen.status = 200;
      out.put("revision", b.revision()).put("etag", b.etag()).put("ir_json", b.irJson());
    } else {
      seen.status = 304;
      ExportedDialogue n = response.body();
      out.put("not_modified", true).put("revision", n.revision()).put("etag", n.etag());
    }
    seen.body = out;
    seen.servedVersion = response.servedVersion().orElse(null);
    seen.renderings.add(String.valueOf(response));
    return seen;
  }

  /**
   * A {@code page_dialogues} step (ADR 29.10.26c D7): the helper iterated to its end, its items as
   * one list under {@code body}, or its error.
   */
  static Observe.Seen pageDialogues(LingaraClient c, JsonNode params) {
    ArrayNode items = Harness.JSON.createArrayNode();
    try {
      for (DialogueSummary item : c.dialogues().pageDialogues(filters(params))) {
        items.add(Harness.JSON.valueToTree(item));
      }
    } catch (RuntimeException e) {
      return Observe.failed(e, List.of(), Optional.empty());
    }
    Observe.Seen seen = new Observe.Seen();
    seen.outcome = "completed";
    seen.body = items;
    return seen;
  }

  /** {@code listDialogues}' or {@code page_dialogues}' params, less the cursor. */
  private static DialogueFilters filters(JsonNode params) {
    DialogueFilters filters = DialogueFilters.of();
    if (params.path("limit").isNumber()) {
      filters = filters.limit(params.path("limit").asInt());
    }
    return params.path("name").isTextual() ? filters.name(params.path("name").asText()) : filters;
  }

  private static <T> T as(JsonNode body, Class<T> type) {
    return Harness.JSON.convertValue(body, type);
  }
}
