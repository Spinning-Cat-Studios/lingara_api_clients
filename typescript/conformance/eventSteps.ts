// The harness's event pieces (conformance/README.md, ADR 30.9.26aa D9): the
// `events` and `tail` steps, which drive the helpers rather than an
// operation, and the `InboundEvent` a `sendEvent` call is built from; and the
// canvas group's call shape and its `page_canvases` step (ADR 16.10.26ak).

import { InboundEvent, UnknownEvent, type EventFeed, type EventTail, type EventsParams, type Lingara } from "@lingara/api";

import type { Observed } from "./compare.js";

export interface HelperStep {
  cursor?: string;
  start?: "latest" | "oldest";
  types?: string[];
  /** `tail` only: how many events to take before stopping it. */
  take?: number;
}

/** `raised` is a raised error and `result` a completed call's value, both rendered for `redacted`. */
export type HelperRun = Omit<Observed, "sleepsS" | "hookCalls" | "renderings"> & { raised?: unknown; result?: unknown };

type ErrorFields = (e: unknown) => { variant: string; fields: Record<string, unknown> };

/**
 * Iterates `client.events(…)` to its end, or takes `take` events from
 * `client.tailEvents(…)` and then stops it (the outcome is `completed`: a
 * tail never ends on its own).
 */
export async function runHelper(client: Lingara, kind: "events" | "tail", step: HelperStep, errorFields: ErrorFields): Promise<HelperRun> {
  const params: EventsParams = { cursor: step.cursor, start: step.start, types: step.types };
  const helper: EventFeed | EventTail = kind === "events" ? client.events(params) : client.tailEvents(params);
  const eventIds: string[] = [];
  const unknownTypes: string[] = [];
  const seen = () => ({ events: [], eventIds, unknownTypes, cursor: helper.cursor });
  try {
    for await (const event of helper) {
      eventIds.push(event.id);
      if (event instanceof UnknownEvent) unknownTypes.push(event.type);
      if (eventIds.length === step.take) break;
    }
    return { outcome: "completed", ...seen() };
  } catch (e) {
    return { outcome: "error", ...seen(), error: errorFields(e), raised: e };
  }
}

/** The seven canvas operations, called on `client.canvases` (ADR 16.10.26ak). */
export const CANVAS_OPS = new Set(["listCanvases", "createCanvas", "getCanvas", "renameCanvas", "saveCanvasDraft", "activateCanvas", "dryRunCanvasPlan"]);

/** A canvas call's arguments before its options: the id as `{ canvasId }`, then the body; or the params; or the body. */
export function canvasArgs(params: Record<string, unknown> | undefined, body: unknown): unknown[] {
  if (params?.["canvas_id"] !== undefined) {
    const ref = { canvasId: params["canvas_id"] };
    return body === undefined ? [ref] : [ref, body];
  }
  return [params ?? body ?? {}];
}

/** A `page_canvases` step (ADR 16.10.26ak D7): the helper iterated to its end, its items as one list under `body`. */
export async function runPageCanvases(client: Lingara, params: Record<string, unknown> | undefined, errorFields: ErrorFields): Promise<HelperRun> {
  const items: unknown[] = [];
  try {
    for await (const item of client.canvases.pageCanvases(params ?? {})) items.push(item);
    return { outcome: "completed", events: [], body: items };
  } catch (e) {
    return { outcome: "error", events: [], error: errorFields(e), raised: e };
  }
}

/** The case's `{type, data}` body as an `InboundEvent`, through the public constructors. */
export function inboundEvent(body: unknown): InboundEvent {
  const { type, data } = body as { type: string; data: never };
  const make = Object.values(InboundEvent).find((construct) => construct(data).type === type);
  if (!make) throw new Error(`no InboundEvent constructor for ${type}`);
  return make(data);
}
