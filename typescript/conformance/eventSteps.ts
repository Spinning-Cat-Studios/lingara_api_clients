// The harness's event pieces (conformance/README.md, ADR 30.9.26aa D9): the
// `events` and `tail` steps, which drive the helpers rather than an
// operation, and the `InboundEvent` a `sendEvent` call is built from.

import { InboundEvent, UnknownEvent, type EventFeed, type EventTail, type EventsParams, type Lingara } from "@lingara/api";

import type { Observed } from "./compare.js";

export interface HelperStep {
  cursor?: string;
  start?: "latest" | "oldest";
  types?: string[];
  /** `tail` only: how many events to take before stopping it. */
  take?: number;
}

export type HelperRun = Omit<Observed, "sleepsS" | "hookCalls" | "renderings"> & { raised?: unknown };

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

/** The case's `{type, data}` body as an `InboundEvent`, through the public constructors. */
export function inboundEvent(body: unknown): InboundEvent {
  const { type, data } = body as { type: string; data: never };
  const make = Object.values(InboundEvent).find((construct) => construct(data).type === type);
  if (!make) throw new Error(`no InboundEvent constructor for ${type}`);
  return make(data);
}
