// Canvases (ADR 16.10.26ak): the seven `/v1/canvases` operations and the
// page helper, on a group the client hands out as `client.canvases`. The
// group holds no state of its own: every call goes through the client's
// request path, so the token, retries, version pin and errors are the
// client's.
//
// A stale `draft_version` is `409 canvas_conflict`, raised as an `ApiError`
// and never retried: re-read with `getCanvas`, compare drafts, and decide.
// Branch on an error's `code`, never its localised `message`. A dry-run
// answers 200 whether or not `ok` is true; a refused verdict's `code` and
// English `error` are data. There is no delete: a canvas is deleted through
// the platform's lifecycle routes, not this library.

import type { CallOptions, WithServedVersion } from "./client.js";
import { TransportError } from "./errors.js";
import type {
  ActivateCanvasRequest,
  Canvas,
  CanvasDryRun,
  CanvasPage,
  CanvasPlan,
  CanvasSummary,
  CreateCanvasRequest,
  RenameCanvasRequest,
  SaveCanvasDraftRequest,
} from "./models.js";
import type { Send } from "./request.js";
import { encodeSegment } from "./transport.js";

/** What `listCanvases` filters by, and what `pageCanvases` resends on every page. */
export interface CanvasFilters {
  /** The page size, 1–50. */
  limit?: number | undefined;
  /** A substring of the canvas name. */
  name?: string | undefined;
  status?: "all" | "live" | "changed" | "unapplied" | undefined;
  /** Each list is sent comma-separated as one value. */
  recipes?: readonly string[] | undefined;
  kinds?: readonly string[] | undefined;
  scopes?: readonly string[] | undefined;
}

/** `listCanvases`' query: the filters and the page to start from. */
export interface ListCanvasesParams extends CanvasFilters {
  /** A previous page's `next_cursor`. */
  cursor?: string | undefined;
}

/** A canvas's id, one encoded path segment. */
export interface CanvasRef {
  canvasId: string;
}

/** The client's JSON request path, with the token every canvas call needs. */
export type CanvasRequest = <T>(path: string, options: CallOptions, extra?: Partial<Send>) => Promise<WithServedVersion<T>>;

/** The canvas operations (`canvas:read`, `canvas:write`), as `client.canvases`. */
export class Canvases {
  readonly #request: CanvasRequest;

  /** Built by the client; reach it as `client.canvases`. */
  constructor(request: CanvasRequest) {
    this.#request = request;
  }

  /** One page of canvases; `pageCanvases` walks them. */
  listCanvases(params: ListCanvasesParams = {}, options: CallOptions = {}): Promise<WithServedVersion<CanvasPage>> {
    return this.#request(`/v1/canvases${canvasQuery(params)}`, options);
  }

  /** Creates a canvas (`201`). The answer does not echo `recipe`. */
  createCanvas(body: CreateCanvasRequest, options: CallOptions = {}): Promise<WithServedVersion<Canvas>> {
    return this.#request("/v1/canvases", options, { method: "POST", body });
  }

  /** One canvas, its draft and active revision inline. */
  getCanvas(params: CanvasRef, options: CallOptions = {}): Promise<WithServedVersion<Canvas>> {
    return this.#request(canvasPath(params), options);
  }

  /** Renames a canvas (`PATCH`). */
  renameCanvas(params: CanvasRef, body: RenameCanvasRequest, options: CallOptions = {}): Promise<WithServedVersion<Canvas>> {
    return this.#request(canvasPath(params), options, { method: "PATCH", body });
  }

  /**
   * Saves the draft (`PUT`). A stale `draft_version` is `409 canvas_conflict`, which may follow your
   * own retried save: re-read with `getCanvas` and compare before you decide.
   */
  saveCanvasDraft(params: CanvasRef, body: SaveCanvasDraftRequest, options: CallOptions = {}): Promise<WithServedVersion<Canvas>> {
    return this.#request(`${canvasPath(params)}/draft`, options, { method: "PUT", body });
  }

  /** Promotes a draft with the objects an apply made. A replayed activate is `409 canvas_conflict` and writes nothing. */
  activateCanvas(params: CanvasRef, body: ActivateCanvasRequest, options: CallOptions = {}): Promise<WithServedVersion<Canvas>> {
    return this.#request(`${canvasPath(params)}/activate`, options, { method: "POST", body });
  }

  /** Judges a plan without applying it. A plan with a refused op still answers 200, `ok: false`. */
  dryRunCanvasPlan(body: CanvasPlan, options: CallOptions = {}): Promise<WithServedVersion<CanvasDryRun>> {
    return this.#request("/v1/canvases/dry-run", options, { method: "POST", body });
  }

  /**
   * Every canvas the filters match, following `next_cursor` until a page says `has_more: false`,
   * the filters resent on every page. It never sleeps or polls and stops on the first error. A page
   * with `has_more` and no `next_cursor` is `TransportError("malformed_response")`, its items unyielded.
   */
  async *pageCanvases(params: CanvasFilters = {}, options: CallOptions = {}): AsyncGenerator<CanvasSummary, void, undefined> {
    let cursor: string | undefined;
    for (;;) {
      const page = await this.listCanvases({ ...params, cursor }, options);
      if (page.has_more && page.next_cursor == null) throw new TransportError("malformed_response");
      yield* page.items;
      if (!page.has_more) return;
      cursor = page.next_cursor ?? undefined;
    }
  }
}

function canvasPath(params: CanvasRef): string {
  return `/v1/canvases/${encodeSegment(params.canvasId)}`;
}

/** The query string for `listCanvases`, each list comma-separated as one value. */
function canvasQuery(params: ListCanvasesParams): string {
  const q = new URLSearchParams();
  const single = { cursor: params.cursor, limit: params.limit?.toString(), name: params.name, status: params.status };
  for (const [key, value] of Object.entries(single)) if (value !== undefined) q.set(key, value);
  const lists = { recipes: params.recipes, kinds: params.kinds, scopes: params.scopes };
  for (const [key, value] of Object.entries(lists)) if (value !== undefined && value.length > 0) q.set(key, value.join(","));
  const text = q.toString();
  return text === "" ? "" : `?${text}`;
}
