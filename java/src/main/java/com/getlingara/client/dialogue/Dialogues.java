package com.getlingara.client.dialogue;

import com.getlingara.client.ApiResponse;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import com.getlingara.client.model.ActivateDialogueRequest;
import com.getlingara.client.model.CreateDialogueRequest;
import com.getlingara.client.model.Dialogue;
import com.getlingara.client.model.DialoguePage;
import com.getlingara.client.model.DialogueSummary;
import com.getlingara.client.model.RenameDialogueRequest;
import com.getlingara.client.model.SaveDialogueDraftRequest;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * The dialogue operations (scopes {@code dialogues:read}, {@code dialogues:write}; ADR 29.10.26c),
 * reached as {@code client.dialogues()}. The group holds no state of its own: every call goes
 * through the client's request path, so the token, retries, version pin and errors are the
 * client's.
 *
 * <p>A stale {@code draft_version} is {@code 409 dialogue_conflict}, thrown as an {@code
 * ApiException} and never retried: re-read with {@link #getDialogue}, take its draft's {@code
 * draft_version} (or {@code 0} when there is no draft), and decide. Branch on an error's {@code
 * code}, never its localised message. There is no delete: a dialogue is deleted through the
 * platform's lifecycle routes, not this library.
 */
public final class Dialogues {
  /** The client's request path, with the token every dialogue call needs. */
  public interface Requests {
    /**
     * Sends one JSON request and decodes its answer.
     *
     * @param method the HTTP method
     * @param path the path, with its query
     * @param body the JSON body, or null for none
     * @param type the answer's type
     * @param <T> the answer's type
     * @return the answer and the served version
     */
    <T> ApiResponse<T> send(String method, String path, Object body, Class<T> type);

    /**
     * Sends one {@code GET} that may answer {@code allowed} as well as a 2xx, and hands the whole
     * answer, undecoded, to {@code decode}.
     *
     * @param path the path, with its query
     * @param headers the request's own headers
     * @param allowed the one non-2xx status read rather than raised
     * @param decode what turns the answer into the result
     * @param <T> the result's type
     * @return the result and the served version
     */
    <T> ApiResponse<T> read(
        String path, Map<String, String> headers, int allowed, Function<Exchange, T> decode);
  }

  /**
   * An answer as {@link Requests#read} hands it over.
   *
   * @param status the status
   * @param headers the response headers
   * @param body the body's bytes, empty when there were none
   */
  public record Exchange(int status, HttpHeaders headers, byte[] body) {}

  private static final int NOT_MODIFIED = 304;

  private final Requests requests;

  /**
   * Built by the client; reach it as {@code client.dialogues()}.
   *
   * @param requests the client's request path
   */
  public Dialogues(Requests requests) {
    this.requests = requests;
  }

  /**
   * One page of dialogues; {@link #pageDialogues} walks them.
   *
   * @param params the filters and the cursor
   * @return the page
   */
  public ApiResponse<DialoguePage> listDialogues(ListDialoguesParams params) {
    return requests.send("GET", "/v1/dialogues" + query(params), null, DialoguePage.class);
  }

  /**
   * Creates a dialogue ({@code 201}), its definition saved as the first draft. A definition the
   * server cannot accept is {@code 400 dialogue_invalid} or {@code dialogue_graph_invalid}.
   *
   * @param body the definition and, optionally, the name
   * @return the dialogue, with any warnings
   */
  public ApiResponse<Dialogue> createDialogue(CreateDialogueRequest body) {
    return requests.send("POST", "/v1/dialogues", body, Dialogue.class);
  }

  /**
   * One dialogue, its draft and active revision inline.
   *
   * @param dialogueId the dialogue, sent as one encoded path segment
   * @return the dialogue
   */
  public ApiResponse<Dialogue> getDialogue(String dialogueId) {
    return requests.send("GET", path(dialogueId), null, Dialogue.class);
  }

  /**
   * Renames a dialogue ({@code PATCH}).
   *
   * @param dialogueId the dialogue
   * @param body the new name
   * @return the dialogue
   */
  public ApiResponse<Dialogue> renameDialogue(String dialogueId, RenameDialogueRequest body) {
    return requests.send("PATCH", path(dialogueId), body, Dialogue.class);
  }

  /**
   * Saves the draft ({@code PUT}). Send the {@code draft_version} you read it at, or {@code 0} to
   * open a draft where there is none. A stale one is {@code 409 dialogue_conflict}, which may
   * follow your own retried save: re-read with {@link #getDialogue} and compare before you decide.
   *
   * @param dialogueId the dialogue
   * @param body the definition and the {@code draft_version} it was read at
   * @return the dialogue
   */
  public ApiResponse<Dialogue> saveDialogueDraft(String dialogueId, SaveDialogueDraftRequest body) {
    return requests.send("PUT", path(dialogueId) + "/draft", body, Dialogue.class);
  }

  /**
   * Promotes the draft to the active revision. A stale or missing draft is {@code 409
   * dialogue_conflict}, as is a replay of an activate that already landed.
   *
   * @param dialogueId the dialogue
   * @param body the {@code draft_version} to promote
   * @return the dialogue
   */
  public ApiResponse<Dialogue> activateDialogue(String dialogueId, ActivateDialogueRequest body) {
    return requests.send("POST", path(dialogueId) + "/activate", body, Dialogue.class);
  }

  /**
   * Exports a dialogue ({@code dialogues:read}, which an embed token carries for the active
   * revision). Pass the {@code etag} you hold as {@code ifNoneMatch}, and an unchanged revision
   * answers {@link ExportedDialogue.NotModified} with no body; a {@code 304} still spends one unit
   * of the rate budget. The body is returned as text and never parsed, so the bytes you store are
   * the bytes the {@code etag} names. A {@code 200} or {@code 304} missing {@code ETag} or {@code
   * Lingara-Dialogue-Revision}, or with a revision that is not a non-negative integer, is {@code
   * TransportException} {@code malformed_response}. K4's {@code Retry-After} loop applies.
   *
   * @param dialogueId the dialogue, sent as one encoded path segment
   * @param options the revision, the layout and the {@code etag} you hold, each sent only when set
   * @return the document, or {@code NotModified}
   */
  public ApiResponse<ExportedDialogue> exportDialogue(
      String dialogueId, ExportDialogueOptions options) {
    ExportDialogueOptions o = options == null ? ExportDialogueOptions.of() : options;
    List<String> pairs = new ArrayList<>();
    add(pairs, "revision", o.revision());
    add(pairs, "layout", o.layout());
    String path = path(dialogueId) + "/export" + joined(pairs);
    Map<String, String> headers =
        o.ifNoneMatch() == null ? Map.of() : Map.of("If-None-Match", o.ifNoneMatch());
    return requests.read(path, headers, NOT_MODIFIED, Dialogues::exported);
  }

  /** The two headers on either arm, and on a {@code 200} the body as text. */
  private static ExportedDialogue exported(Exchange answer) {
    String revision = answer.headers().firstValue("lingara-dialogue-revision").orElse(null);
    String etag = answer.headers().firstValue("etag").orElse(null);
    if (revision == null || etag == null || !revision.matches("[0-9]+")) {
      throw malformed();
    }
    long number;
    try {
      number = Long.parseLong(revision);
    } catch (NumberFormatException e) {
      throw malformed();
    }
    if (answer.status() == NOT_MODIFIED) {
      return new ExportedDialogue.NotModified(number, etag);
    }
    try {
      String text =
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(answer.body())).toString();
      return new ExportedDialogue.Body(number, etag, text);
    } catch (CharacterCodingException e) {
      throw malformed();
    }
  }

  private static TransportException malformed() {
    return new TransportException(TransportKind.MALFORMED_RESPONSE, null);
  }

  /**
   * Every dialogue the filters match, following {@code next_cursor} until a page says {@code
   * has_more: false}, the filters resent on every page. Each iteration fetches lazily from the
   * first page; it never sleeps or polls and stops on the first error. A page with {@code has_more}
   * and no {@code next_cursor} is {@code TransportException} {@code malformed_response}, its items
   * unreturned.
   *
   * @param filters what to keep, and the page size
   * @return the dialogues' list items, one page fetched at a time
   */
  public Iterable<DialogueSummary> pageDialogues(DialogueFilters filters) {
    return () -> new Pages(filters);
  }

  private final class Pages implements Iterator<DialogueSummary> {
    private final DialogueFilters filters;
    private final ArrayDeque<DialogueSummary> items = new ArrayDeque<>();
    private String cursor;
    private boolean more = true;

    Pages(DialogueFilters filters) {
      this.filters = filters;
    }

    @Override
    public boolean hasNext() {
      while (items.isEmpty() && more) {
        DialoguePage page = listDialogues(new ListDialoguesParams(cursor, filters)).body();
        boolean hasMore = Boolean.TRUE.equals(page.getHasMore());
        if (hasMore && page.getNextCursor() == null) {
          more = false;
          throw malformed();
        }
        items.addAll(page.getItems() == null ? List.of() : page.getItems());
        cursor = page.getNextCursor();
        more = hasMore;
      }
      return !items.isEmpty();
    }

    @Override
    public DialogueSummary next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.poll();
    }
  }

  private static String path(String dialogueId) {
    return "/v1/dialogues/" + encode(dialogueId);
  }

  /** The list's query, each value encoded. */
  static String query(ListDialoguesParams params) {
    DialogueFilters f = params.filters();
    List<String> pairs = new ArrayList<>();
    add(pairs, "cursor", params.cursor());
    add(pairs, "limit", f.limit() == null ? null : f.limit().toString());
    add(pairs, "name", f.name());
    return joined(pairs);
  }

  private static String joined(List<String> pairs) {
    return pairs.isEmpty() ? "" : "?" + String.join("&", pairs);
  }

  private static void add(List<String> pairs, String key, String value) {
    if (value != null) {
      pairs.add(key + "=" + encode(value));
    }
  }

  /** Percent-encodes everything but {@code A–Z a–z 0–9 - . _ ~}, as the client does. */
  private static String encode(String value) {
    StringBuilder b = new StringBuilder();
    for (byte c : value.getBytes(StandardCharsets.UTF_8)) {
      if ((c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z')
          || (c >= '0' && c <= '9')
          || "-._~".indexOf(c) >= 0) {
        b.append((char) c);
      } else {
        b.append(String.format("%%%02X", c & 0xFF));
      }
    }
    return b.toString();
  }
}
