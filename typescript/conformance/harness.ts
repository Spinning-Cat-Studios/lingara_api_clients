// The conformance harness (conformance/README.md, Writing a harness).
//
// Built against the built package, never `src/`: `@lingara/api` resolves to
// ../../dist/index.mjs. Every client is built from the case's `client` block
// through the public options only, with a virtual clock and a recording
// sleeper. Runs unchanged on Node, Deno and Bun.

import { appendFileSync, readFileSync } from "node:fs";
import { createServer } from "node:net";

import {
  ApiError,
  EventStream,
  Lingara,
  LingaraError,
  MaintenanceError,
  OAuthError,
  TransportError,
  type DeprecationNotice,
  type LingaraOptions,
} from "@lingara/api";

import { compare, render, substitute, type Expect, type Observed } from "./compare.js";

const env = (name: string): string => {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: run this through conformance-server run`);
  return value;
};

const BASE_URL = env("LINGARA_CONFORMANCE_BASE_URL");
const TOKEN_URL = env("LINGARA_CONFORMANCE_TOKEN_URL");
const CONTROL = env("LINGARA_CONFORMANCE_CONTROL_URL");
const OUT = env("LINGARA_CONFORMANCE_OUT");
const ONLY = process.env["LINGARA_CONFORMANCE_ONLY"];
const CLOCK_START_S = 1_790_000_000;
// The version of the package under test, read from beside the bundle.
const LIBRARY_VERSION = (JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as { version: string }).version;

interface CaseClient {
  credentials?: { client_id: string; client_secret: string; auth: "basic" | "post" };
  scopes?: string[];
  version?: string;
  retries?: { max_attempts?: number; retry_after_cap_s?: number };
  deprecation_hook?: "record";
  user_agent_suffix?: string;
  stream_idle_timeout_ms?: number;
  base_url?: "unreachable";
}

interface Call {
  operation: string;
  params?: Record<string, unknown>;
  body?: unknown;
  parallel?: number;
  cancel_after_events?: number;
}

interface Case {
  id: string;
  client?: CaseClient;
  steps: { call?: Call; expect?: Expect; advance_clock_s?: number }[];
}

/** One case's client, its virtual clock, its sleeps and its hook calls. */
interface Rig {
  client: Lingara;
  advance(seconds: number): void;
  sleeps: number[];
  hookCalls: unknown[];
}

async function control(method: "GET" | "POST", path: string): Promise<unknown> {
  const res = await fetch(`${CONTROL}${path}`, { method });
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status} ${await res.text()}`);
  return res.json();
}

/** A port nothing listens on: bind one, then close it. */
async function closedPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      server.close(() => resolve(port));
    });
  });
}

function hookRecord(n: DeprecationNotice): unknown {
  const seconds = (d?: Date) => (d ? Math.floor(d.getTime() / 1000) : null);
  const link = n.link ? { raw: n.link.raw, target: n.link.url?.href ?? null } : null;
  return { version: n.version ?? null, deprecated_at: seconds(n.deprecatedAt), sunset_at: seconds(n.sunsetAt), link };
}

async function rig(c: CaseClient = {}): Promise<Rig> {
  let now = CLOCK_START_S * 1000;
  const sleeps: number[] = [];
  const hookCalls: unknown[] = [];
  const base = c.base_url === "unreachable" ? `http://127.0.0.1:${await closedPort()}` : BASE_URL;
  const options: LingaraOptions = {
    ...caseOptions(c),
    baseUrl: base,
    tokenUrl: c.base_url === "unreachable" ? `${base}/oauth/token` : TOKEN_URL,
    clock: { now: () => now },
    sleeper: async (ms, signal) => {
      signal?.throwIfAborted();
      sleeps.push(ms);
    },
  };
  if (c.deprecation_hook === "record") options.onDeprecation = (n) => void hookCalls.push(hookRecord(n));
  return { client: new Lingara(options), advance: (s) => (now += s * 1000), sleeps, hookCalls };
}

/** The case's `client` block as public options, absent keys left out. */
function caseOptions(c: CaseClient): LingaraOptions {
  const cred = c.credentials;
  const all = {
    clientId: cred?.client_id,
    clientSecret: cred?.client_secret,
    auth: cred?.auth,
    scopes: c.scopes,
    version: c.version,
    maxAttempts: c.retries?.max_attempts,
    retryAfterCapSeconds: c.retries?.retry_after_cap_s,
    userAgentSuffix: c.user_agent_suffix,
    streamIdleTimeoutMs: c.stream_idle_timeout_ms,
  };
  return Object.fromEntries(Object.entries(all).filter(([, v]) => v !== undefined)) as LingaraOptions;
}

/** The contract's snake_case fields of a raised error. */
function errorFields(e: unknown): { variant: string; fields: Record<string, unknown> } {
  if (e instanceof ApiError) {
    const { status, code, message, retryAfter, planId, servedVersion } = e;
    return { variant: "ApiError", fields: { status, code, message, retry_after: retryAfter, plan_id: planId, served_version: servedVersion } };
  }
  if (e instanceof OAuthError) {
    return { variant: "OAuthError", fields: { status: e.status, error: e.error, description: e.description, retry_after: e.retryAfter } };
  }
  if (e instanceof MaintenanceError) return { variant: "MaintenanceError", fields: { body: e.body, retry_after: e.retryAfter } };
  if (e instanceof TransportError) return { variant: "TransportError", fields: { kind: e.kind } };
  const variant = e instanceof LingaraError ? "LingaraError" : `not a library error: ${String(e)}`;
  return { variant, fields: {} };
}

type Run = Omit<Observed, "sleepsS" | "hookCalls" | "renderings"> & { raised?: unknown };

/** Runs one call to completion, error or cancellation. */
async function invoke(r: Rig, call: Call): Promise<Run> {
  const ac = new AbortController();
  const options = { signal: ac.signal };
  const fn = (r.client as unknown as Record<string, (...args: unknown[]) => unknown>)[call.operation];
  if (!fn) throw new Error(`no operation ${call.operation}`);
  const first = call.params ?? call.body;
  const result = first === undefined ? fn.call(r.client, options) : fn.call(r.client, first, options);
  const events: unknown[] = [];
  try {
    if (result instanceof EventStream) return await drain(result, events, call, ac);
    const body = (await result) as { servedVersion?: string };
    return { outcome: "completed", status: 200, body, events, servedVersion: body.servedVersion };
  } catch (e) {
    const servedVersion = result instanceof EventStream ? await result.servedVersion : undefined;
    if (ac.signal.aborted && e === ac.signal.reason) return { outcome: "cancelled", events, servedVersion };
    return { outcome: "error", events, error: errorFields(e), servedVersion, raised: e };
  }
}

/** Iterates a stream, cancelling with the caller's signal after n events. */
async function drain(stream: EventStream<{ event: string }>, events: unknown[], call: Call, ac: AbortController): Promise<Run> {
  for await (const ev of stream) {
    events.push(ev);
    if (events.length === call.cancel_after_events) ac.abort();
  }
  return { outcome: "completed", status: 200, events, servedVersion: await stream.servedVersion };
}

async function runStep(r: Rig, call: Call, expect: Expect): Promise<string[]> {
  r.sleeps.length = 0;
  r.hookCalls.length = 0;
  const runs = await Promise.all(Array.from({ length: call.parallel ?? 1 }, () => invoke(r, call)));
  const sleepsS = r.sleeps.map((ms) => Math.round(ms / 1000));
  return runs.flatMap((run, i) => {
    const renderings = [...render(r.client), ...render(r.client.tokenSource), ...render(run.raised)];
    const seen: Observed = { ...run, sleepsS, hookCalls: [...r.hookCalls], renderings };
    const label = runs.length > 1 ? `call ${i + 1}: ` : "";
    return compare(expect, seen).map((m) => `${call.operation}: ${label}${m}`);
  });
}

async function runCase(id: string): Promise<boolean> {
  const started = Date.now();
  const c = (await control("GET", `/cases/${id}`)) as Case;
  await control("POST", `/cases/${id}/arm`);
  const clientMismatches: string[] = [];
  try {
    const r = await rig(c.client);
    for (const step of c.steps) {
      if (step.advance_clock_s !== undefined) r.advance(step.advance_clock_s);
      if (step.call && step.expect) clientMismatches.push(...(await runStep(r, step.call, substitute(step.expect, BASE_URL) as Expect)));
    }
  } catch (e) {
    clientMismatches.push(`harness: ${e instanceof Error ? e.message : String(e)}`);
  }
  const verdict = (await control("POST", `/cases/${id}/finish`)) as { mismatches: unknown[] };
  const line = {
    case: id,
    lang: "typescript",
    library_version: LIBRARY_VERSION,
    result: clientMismatches.length === 0 && verdict.mismatches.length === 0 ? "pass" : "fail",
    client_mismatches: clientMismatches,
    server_mismatches: verdict.mismatches,
    duration_ms: Date.now() - started,
  };
  appendFileSync(OUT, `${JSON.stringify(line)}\n`);
  if (line.result === "pass") return true;
  console.error(`✗ ${id}: ${JSON.stringify({ client: clientMismatches, server: verdict.mismatches })}`);
  return false;
}

async function main(): Promise<void> {
  const only = ONLY ? new Set(ONLY.split(",").map((s) => s.trim()).filter(Boolean)) : undefined;
  const ids = ((await control("GET", "/cases")) as string[]).filter((id) => !only || only.has(id));
  let failed = 0;
  for (const id of ids) {
    if (!(await runCase(id))) failed++;
  }
  process.exitCode = failed === 0 ? 0 : 1;
}

await main();
