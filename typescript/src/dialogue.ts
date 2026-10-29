// Stored dialogues (ADR 29.10.26c): the seven `/v1/dialogues` operations and
// the page helper, on a group the client hands out as `client.dialogues`.
// The group holds no state of its own: every call goes through the client's
// request path, so the token, retries, version pin and errors are the
// client's.
//
// A stale `draft_version` is `409 dialogue_conflict`, raised as an `ApiError`
// and never retried: re-read with `getDialogue`, take `draft.draft_version`
// (or send 0 when `draft` is null), and decide. Branch on an error's `code`,
// never its localised `message`. The export is read by hand: its body is
// canonical bytes never parsed here, its two headers are part of the answer,
// and a `304` is an answer. There is no delete: a dialogue is deleted through
// the platform's lifecycle routes, not this library.

import type { CallOptions, WithServedVersion } from "./client.js";
import { TransportError } from "./errors.js";
import type {
  ActivateDialogueRequest,
  CreateDialogueRequest,
  Dialogue,
  DialoguePage,
  DialogueSummary,
  RenameDialogueRequest,
  SaveDialogueDraftRequest,
} from "./models.js";
import type { RawResponse, Send } from "./request.js";
import { encodeSegment, transportFailure } from "./transport.js";

/** What `listDialogues` filters by, and what `pageDialogues` resends on every page. */
export interface DialogueFilters {
  /** The page size, 1–50. */
  limit?: number | undefined;
  /** A substring of the dialogue name. */
  name?: string | undefined;
}

/** `listDialogues`' query: the filters and the page to start from. */
export interface ListDialoguesParams extends DialogueFilters {
  /** A previous page's `next_cursor`. */
  cursor?: string | undefined;
}

/** A dialogue's id, one encoded path segment. */
export interface DialogueRef {
  dialogueId: string;
}

/** `exportDialogue`'s input: the id, and each option sent only when given. */
export interface ExportDialogueParams extends DialogueRef {
  /** `active` (the server's default) or `draft`. */
  revision?: "active" | "draft" | undefined;
  /** `keep` (the server's default) or `omit`, which drops every `x-` member and has its own `ETag`. */
  layout?: "keep" | "omit" | undefined;
  /** The `etag` of the copy you hold, sent back exactly as received. */
  ifNoneMatch?: string | undefined;
}

/**
 * What `exportDialogue` answers: the `dialogue.v1` document unparsed, its bytes canonical and `etag`
 * their SHA-256; or, on a `304`, `notModified`: keep the copy you hold.
 */
export type ExportedDialogue =
  | { kind: "body"; revision: number; etag: string; irJson: string; servedVersion?: string | undefined }
  | { kind: "notModified"; revision: number; etag: string; servedVersion?: string | undefined };

/** The client's JSON request path, with the token every dialogue call needs. */
export type DialogueRequest = <T>(path: string, options: CallOptions, extra?: Partial<Send>) => Promise<WithServedVersion<T>>;

/** The client's request path, resolving with the response itself (the export's). */
export type DialogueRaw = (req: Send) => Promise<RawResponse>;

/** The dialogue operations (`dialogues:read`, `dialogues:write`), as `client.dialogues`. */
export class Dialogues {
  readonly #request: DialogueRequest;
  readonly #raw: DialogueRaw;

  /** Built by the client; reach it as `client.dialogues`. */
  constructor(request: DialogueRequest, raw: DialogueRaw) {
    this.#request = request;
    this.#raw = raw;
  }

  /** One page of dialogues; `pageDialogues` walks them. */
  listDialogues(params: ListDialoguesParams = {}, options: CallOptions = {}): Promise<WithServedVersion<DialoguePage>> {
    return this.#request(`/v1/dialogues${query({ cursor: params.cursor, limit: params.limit?.toString(), name: params.name })}`, options);
  }

  /** Creates a dialogue (`201`). Its `warnings` are advice, never a refusal. */
  createDialogue(body: CreateDialogueRequest, options: CallOptions = {}): Promise<WithServedVersion<Dialogue>> {
    return this.#request("/v1/dialogues", options, { method: "POST", body });
  }

  /** One dialogue, its draft and active revision inline. */
  getDialogue(params: DialogueRef, options: CallOptions = {}): Promise<WithServedVersion<Dialogue>> {
    return this.#request(dialoguePath(params), options);
  }

  /** Renames a dialogue (`PATCH`). */
  renameDialogue(params: DialogueRef, body: RenameDialogueRequest, options: CallOptions = {}): Promise<WithServedVersion<Dialogue>> {
    return this.#request(dialoguePath(params), options, { method: "PATCH", body });
  }

  /**
   * Saves the draft (`PUT`); `draft_version: 0` with no draft opens one. A stale `draft_version` is
   * `409 dialogue_conflict`, which may follow your own retried save: re-read with `getDialogue` and
   * compare before you decide.
   */
  saveDialogueDraft(params: DialogueRef, body: SaveDialogueDraftRequest, options: CallOptions = {}): Promise<WithServedVersion<Dialogue>> {
    return this.#request(`${dialoguePath(params)}/draft`, options, { method: "PUT", body });
  }

  /** Promotes the draft. A stale or missing draft is `409 dialogue_conflict` and writes nothing. */
  activateDialogue(params: DialogueRef, body: ActivateDialogueRequest, options: CallOptions = {}): Promise<WithServedVersion<Dialogue>> {
    return this.#request(`${dialoguePath(params)}/activate`, options, { method: "POST", body });
  }

  /**
   * Exports a dialogue (`dialogues:read`, which an embed token carries for the active revision). Pass
   * the `etag` you hold as `ifNoneMatch`, and an unchanged revision answers `notModified` with no body;
   * a `304` still spends one unit of the rate budget. A missing or malformed `ETag` or
   * `Lingara-Dialogue-Revision` is `TransportError("malformed_response")`. K4's retries apply.
   */
  async exportDialogue(params: ExportDialogueParams, options: CallOptions = {}): Promise<ExportedDialogue> {
    const headers: Record<string, string> = params.ifNoneMatch === undefined ? {} : { "if-none-match": params.ifNoneMatch };
    const path = `${dialoguePath(params)}/export${query({ revision: params.revision, layout: params.layout })}`;
    const send: Send = { method: "GET", path, accept: "application/json", needsToken: true, headers, allow: 304, signal: options.signal };
    const { response, servedVersion } = await this.#raw(send);
    const revision = response.headers.get("lingara-dialogue-revision");
    const etag = response.headers.get("etag");
    if (revision === null || !/^\d+$/.test(revision) || etag === null) {
      await response.body?.cancel().catch(() => undefined);
      throw new TransportError("malformed_response");
    }
    if (response.status === 304) return { kind: "notModified", revision: Number(revision), etag, servedVersion };
    const irJson = await response.text().catch((err: unknown) => {
      throw transportFailure(err, options.signal, "body", []);
    });
    return { kind: "body", revision: Number(revision), etag, irJson, servedVersion };
  }

  /**
   * Every dialogue the filters match, following `next_cursor` until a page says `has_more: false`, the
   * filters resent on every page. It never sleeps or polls and stops on the first error. A page with
   * `has_more` and no `next_cursor` is `TransportError("malformed_response")`, its items unyielded.
   */
  async *pageDialogues(params: DialogueFilters = {}, options: CallOptions = {}): AsyncGenerator<DialogueSummary, void, undefined> {
    let cursor: string | undefined;
    for (;;) {
      const page = await this.listDialogues({ ...params, cursor }, options);
      if (page.has_more && page.next_cursor == null) throw new TransportError("malformed_response");
      yield* page.items;
      if (!page.has_more) return;
      cursor = page.next_cursor ?? undefined;
    }
  }
}

function dialoguePath(params: DialogueRef): string {
  return `/v1/dialogues/${encodeSegment(params.dialogueId)}`;
}

/** A query string from the given values, in the order given; `""` when none is. */
function query(values: Record<string, string | undefined>): string {
  const q = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value !== undefined) q.set(key, value);
  const text = q.toString();
  return text === "" ? "" : `?${text}`;
}
