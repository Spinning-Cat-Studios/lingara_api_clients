// The harness's dialogue pieces (ADR 29.10.26c D7): the group's call shape,
// the export's reported object, and the `page_dialogues` step.

import type { ExportedDialogue, Lingara } from "@lingara/api";

import { CANVAS_OPS, type HelperRun } from "./eventSteps.js";

type ErrorFields = (e: unknown) => { variant: string; fields: Record<string, unknown> };

/** The seven dialogue operations, called on `client.dialogues`. */
export const DIALOGUE_OPS = new Set(["listDialogues", "createDialogue", "getDialogue", "renameDialogue", "saveDialogueDraft", "activateDialogue", "exportDialogue"]);

/** The object an operation is called on: a group (`client.canvases`, `client.dialogues`) or the client. */
export function groupOf(client: Lingara, operation: string): object {
  if (CANVAS_OPS.has(operation)) return client.canvases;
  return DIALOGUE_OPS.has(operation) ? client.dialogues : client;
}

/** A dialogue call's arguments before its options: the export's one params object; the id, then the body; or the params; or the body. */
export function dialogueArgs(operation: string, params: Record<string, unknown> | undefined, body: unknown): unknown[] {
  const dialogueId = params?.["dialogue_id"];
  if (operation === "exportDialogue") {
    return [{ dialogueId, revision: params?.["revision"], layout: params?.["layout"], ifNoneMatch: params?.["if_none_match"] }];
  }
  if (dialogueId !== undefined) return body === undefined ? [{ dialogueId }] : [{ dialogueId }, body];
  return [params ?? body ?? {}];
}

/** An export reported as D7's object: `{revision, etag, ir_json}`, or `{not_modified: true, revision, etag}`. */
export function wireExport(value: ExportedDialogue): { status: number; body: unknown } {
  if (value.kind === "notModified") return { status: 304, body: { not_modified: true, revision: value.revision, etag: value.etag } };
  return { status: 200, body: { revision: value.revision, etag: value.etag, ir_json: value.irJson } };
}

/** A `page_dialogues` step: the helper iterated to its end, its items as one list under `body`. */
export async function runPageDialogues(client: Lingara, params: Record<string, unknown> | undefined, errorFields: ErrorFields): Promise<HelperRun> {
  const items: unknown[] = [];
  try {
    for await (const item of client.dialogues.pageDialogues(params ?? {})) items.push(item);
    return { outcome: "completed", events: [], body: items };
  } catch (e) {
    return { outcome: "error", events: [], error: errorFields(e), raised: e };
  }
}
