// How an observed call is compared with a case's `expect` (conformance/
// README.md, Comparison rules). Pure: no I/O, no library import.

import { inspect } from "node:util";

export interface Expect {
  outcome: "completed" | "error" | "cancelled";
  status?: number;
  body?: unknown;
  events?: unknown[];
  error?: { variant: string; fields?: Record<string, unknown> };
  served_version?: string;
  sleeps_s?: number[];
  hook_calls?: unknown[];
  redacted?: string[];
  /** The events and tail steps (ADR 30.9.26aa D9). */
  event_ids?: string[];
  unknown_types?: string[];
  cursor?: Matcher;
}

/** A string matcher, the header matchers' shape. */
export interface Matcher {
  equals?: string;
  prefix?: string;
  contains?: string;
  pattern?: string;
  absent?: boolean;
}

export interface Observed {
  outcome: "completed" | "error" | "cancelled";
  status?: number;
  body?: unknown;
  events: unknown[];
  error?: { variant: string; fields: Record<string, unknown> } | undefined;
  servedVersion?: string | undefined;
  sleepsS: number[];
  hookCalls: unknown[];
  /** Every rendering of the client, the token source and a raised error. */
  renderings: string[];
  /** A helper step's yielded ids, its UnknownEvent types and its final cursor. */
  eventIds?: string[];
  unknownTypes?: string[];
  cursor?: string | undefined;
}

/** JSON with `null`-valued keys dropped and object keys sorted. */
export function canon(value: unknown): string {
  return JSON.stringify(normalise(value));
}

function normalise(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalise);
  if (typeof value !== "object" || value === null) return value;
  const entries = Object.entries(value as Record<string, unknown>)
    .filter(([, v]) => v !== null && v !== undefined)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => [k, normalise(v)]);
  return Object.fromEntries(entries);
}

/** Replaces `{base_url}` in every string of an expected value. */
export function substitute(value: unknown, baseUrl: string): unknown {
  if (typeof value === "string") return value.split("{base_url}").join(baseUrl);
  if (Array.isArray(value)) return value.map((v) => substitute(v, baseUrl));
  if (typeof value !== "object" || value === null) return value;
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, substitute(v, baseUrl)]));
}

function same(label: string, expected: unknown, actual: unknown, out: string[]): void {
  if (canon(expected) !== canon(actual)) out.push(`${label}: expected ${canon(expected)}, got ${canon(actual)}`);
}

/** Every difference between one observed call and its expectation. */
export function compare(expect: Expect, seen: Observed): string[] {
  const out: string[] = [];
  compareOutcome(expect, seen, out);
  const pairs: [string, unknown, unknown][] = [
    ["status", expect.status, seen.status ?? seen.error?.fields["status"]],
    ["body", expect.body, seen.body],
    ["events", expect.events, seen.events],
    ["served_version", expect.served_version, seen.servedVersion],
    ["sleeps_s", expect.sleeps_s, seen.sleepsS],
    ["hook_calls", expect.hook_calls, seen.hookCalls],
    ["event_ids", expect.event_ids, seen.eventIds],
    ["unknown_types", expect.unknown_types, seen.unknownTypes],
  ];
  for (const [label, want, got] of pairs) if (want !== undefined) same(label, want, got, out);
  if (expect.error !== undefined) compareError(expect.error, seen, out);
  if (expect.cursor !== undefined) compareCursor(expect.cursor, seen.cursor, out);
  const leaked = (expect.redacted ?? []).filter((secret) => seen.renderings.some((r) => r.includes(secret)));
  for (const secret of leaked) out.push(`redacted: a rendering contains ${secret.slice(0, 12)}…`);
  return out;
}

function compareOutcome(expect: Expect, seen: Observed, out: string[]): void {
  if (seen.outcome === expect.outcome) return;
  const detail = seen.error ? ` (${seen.error.variant} ${canon(seen.error.fields)})` : "";
  out.push(`outcome: expected ${expect.outcome}, got ${seen.outcome}${detail}`);
}

function compareCursor(expected: Matcher, cursor: string | undefined, out: string[]): void {
  if (!matches(expected, cursor)) out.push(`cursor: expected ${canon(expected)}, got ${canon(cursor ?? null)}`);
}

function matches(m: Matcher, value: string | undefined): boolean {
  if (m.absent !== undefined) return m.absent === (value === undefined);
  if (value === undefined) return false;
  if (m.equals !== undefined) return value === m.equals;
  if (m.prefix !== undefined) return value.startsWith(m.prefix);
  if (m.contains !== undefined) return value.includes(m.contains);
  return m.pattern === undefined || new RegExp(m.pattern).test(value);
}

function compareError(expected: NonNullable<Expect["error"]>, seen: Observed, out: string[]): void {
  if (!seen.error) {
    out.push(`error: expected ${expected.variant}, got none`);
    return;
  }
  if (seen.error.variant !== expected.variant) out.push(`error.variant: expected ${expected.variant}, got ${seen.error.variant}`);
  for (const [field, want] of Object.entries(expected.fields ?? {})) {
    same(`error.${field}`, want, seen.error.fields[field] ?? null, out);
  }
}

/** Debug, string and inspect renderings of a value (and an error's stack). */
export function render(value: unknown): string[] {
  if (value === undefined) return [];
  const stack = (value as { stack?: unknown } | null)?.stack;
  return [inspect(value, { depth: 10 }), JSON.stringify(value) ?? "", String(value), typeof stack === "string" ? stack : ""];
}
