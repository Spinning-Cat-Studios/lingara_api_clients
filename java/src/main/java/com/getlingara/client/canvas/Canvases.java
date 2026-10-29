package com.getlingara.client.canvas;

import com.getlingara.client.ApiResponse;
import com.getlingara.client.TransportException;
import com.getlingara.client.TransportKind;
import com.getlingara.client.model.ActivateCanvasRequest;
import com.getlingara.client.model.Canvas;
import com.getlingara.client.model.CanvasDryRun;
import com.getlingara.client.model.CanvasPage;
import com.getlingara.client.model.CanvasPlan;
import com.getlingara.client.model.CanvasSummary;
import com.getlingara.client.model.CreateCanvasRequest;
import com.getlingara.client.model.RenameCanvasRequest;
import com.getlingara.client.model.SaveCanvasDraftRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The canvas operations (scopes {@code canvas:read}, {@code canvas:write}; ADR 16.10.26ak), reached
 * as {@code client.canvases()}. The group holds no state of its own: every call goes through the
 * client's request path, so the token, retries, version pin and errors are the client's.
 *
 * <p>A stale {@code draft_version} is {@code 409 canvas_conflict}, thrown as an {@code
 * ApiException} and never retried: re-read with {@link #getCanvas}, compare drafts, and decide.
 * Branch on an error's {@code code}, never its localised message. A dry-run answers {@code 200}
 * whether or not {@code ok} is true; a refused verdict's {@code code} and English {@code error} are
 * data. There is no delete: a canvas is deleted through the platform's lifecycle routes, not this
 * library.
 */
public final class Canvases {
  /** The client's JSON request path, with the token every canvas call needs. */
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
  }

  private final Requests requests;

  /**
   * Built by the client; reach it as {@code client.canvases()}.
   *
   * @param requests the client's request path
   */
  public Canvases(Requests requests) {
    this.requests = requests;
  }

  /**
   * One page of canvases; {@link #pageCanvases} walks them.
   *
   * @param params the filters and the cursor
   * @return the page
   */
  public ApiResponse<CanvasPage> listCanvases(ListCanvasesParams params) {
    return requests.send("GET", "/v1/canvases" + query(params), null, CanvasPage.class);
  }

  /**
   * Creates a canvas ({@code 201}). The answer does not echo {@code recipe}.
   *
   * @param body the name, recipe and definition
   * @return the canvas
   */
  public ApiResponse<Canvas> createCanvas(CreateCanvasRequest body) {
    return requests.send("POST", "/v1/canvases", body, Canvas.class);
  }

  /**
   * One canvas, its draft and active revision inline.
   *
   * @param canvasId the canvas, sent as one encoded path segment
   * @return the canvas
   */
  public ApiResponse<Canvas> getCanvas(String canvasId) {
    return requests.send("GET", path(canvasId), null, Canvas.class);
  }

  /**
   * Renames a canvas ({@code PATCH}).
   *
   * @param canvasId the canvas
   * @param body the new name
   * @return the canvas
   */
  public ApiResponse<Canvas> renameCanvas(String canvasId, RenameCanvasRequest body) {
    return requests.send("PATCH", path(canvasId), body, Canvas.class);
  }

  /**
   * Saves the draft ({@code PUT}). A stale {@code draft_version} is {@code 409 canvas_conflict},
   * which may follow your own retried save: re-read with {@link #getCanvas} and compare before you
   * decide.
   *
   * @param canvasId the canvas
   * @param body the definition and the {@code draft_version} it was read at
   * @return the canvas
   */
  public ApiResponse<Canvas> saveCanvasDraft(String canvasId, SaveCanvasDraftRequest body) {
    return requests.send("PUT", path(canvasId) + "/draft", body, Canvas.class);
  }

  /**
   * Promotes a draft with the objects an apply made. A replayed activate at a {@code draft_version}
   * already promoted is {@code 409 canvas_conflict} and writes nothing.
   *
   * @param canvasId the canvas
   * @param body the {@code draft_version}, the outcome and the objects
   * @return the canvas
   */
  public ApiResponse<Canvas> activateCanvas(String canvasId, ActivateCanvasRequest body) {
    return requests.send("POST", path(canvasId) + "/activate", body, Canvas.class);
  }

  /**
   * Judges a plan without applying it. A plan with a refused op still answers {@code 200}, {@code
   * ok: false}.
   *
   * @param body the plan
   * @return the verdicts
   */
  public ApiResponse<CanvasDryRun> dryRunCanvasPlan(CanvasPlan body) {
    return requests.send("POST", "/v1/canvases/dry-run", body, CanvasDryRun.class);
  }

  /**
   * Every canvas the filters match, following {@code next_cursor} until a page says {@code
   * has_more: false}, the filters resent on every page. Each iteration fetches lazily from the
   * first page; it never sleeps or polls and stops on the first error. A page with {@code has_more}
   * and no {@code next_cursor} is {@code TransportException} {@code malformed_response}, its items
   * unreturned.
   *
   * @param filters what to keep, and the page size
   * @return the canvases, one page fetched at a time
   */
  public Iterable<CanvasSummary> pageCanvases(CanvasFilters filters) {
    return () -> new Pages(filters);
  }

  private final class Pages implements Iterator<CanvasSummary> {
    private final CanvasFilters filters;
    private final ArrayDeque<CanvasSummary> items = new ArrayDeque<>();
    private String cursor;
    private boolean more = true;

    Pages(CanvasFilters filters) {
      this.filters = filters;
    }

    @Override
    public boolean hasNext() {
      while (items.isEmpty() && more) {
        CanvasPage page = listCanvases(new ListCanvasesParams(cursor, filters)).body();
        boolean hasMore = Boolean.TRUE.equals(page.getHasMore());
        if (hasMore && page.getNextCursor() == null) {
          more = false;
          throw new TransportException(TransportKind.MALFORMED_RESPONSE, null);
        }
        items.addAll(page.getItems() == null ? List.of() : page.getItems());
        cursor = page.getNextCursor();
        more = hasMore;
      }
      return !items.isEmpty();
    }

    @Override
    public CanvasSummary next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return items.poll();
    }
  }

  private static String path(String canvasId) {
    return "/v1/canvases/" + encode(canvasId);
  }

  /** The query: each value encoded, each list one comma-separated value. */
  static String query(ListCanvasesParams params) {
    CanvasFilters f = params.filters();
    List<String> pairs = new ArrayList<>();
    add(pairs, "cursor", params.cursor());
    add(pairs, "limit", f.limit() == null ? null : f.limit().toString());
    add(pairs, "name", f.name());
    add(pairs, "status", f.status());
    add(pairs, "recipes", f.recipes().isEmpty() ? null : String.join(",", f.recipes()));
    add(pairs, "kinds", f.kinds().isEmpty() ? null : String.join(",", f.kinds()));
    add(pairs, "scopes", f.scopes().isEmpty() ? null : String.join(",", f.scopes()));
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
