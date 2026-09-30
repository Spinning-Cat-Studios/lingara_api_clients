import { inspect } from "node:util";

import { describe, expect, it, vi } from "vitest";

import { Lingara } from "../client.js";
import {
  ApiError,
  LingaraError,
  MaintenanceError,
  OAuthError,
  TransportError,
  errorFromResponse,
  transportKind,
} from "../errors.js";
import { ClientCredentials } from "../token.js";
import { transportFailure } from "../transport.js";
import { fakeFetch, json, tokenOk } from "./fixtures/fakeFetch.js";
import { TRANSPORT_SHAPES } from "./fixtures/transportErrors.js";

const SECRET = "lgr_cs_unitsecret0000000000000000000000000000000";
const TOKEN = "lgr_at_unittoken";

function renderings(value: unknown): string[] {
  const e = value as { stack?: unknown; cause?: unknown };
  return [
    inspect(value, { depth: 10 }),
    JSON.stringify(value) ?? "",
    String(value),
    typeof e.stack === "string" ? e.stack : "",
    e.cause === undefined ? "" : inspect(e.cause, { depth: 10 }),
  ];
}

describe("errors", () => {
  /** 29.9.26o AC8: the secret and the token never render, in any form, and render as [REDACTED]. */
  it("secrets never render", async () => {
    const fake = fakeFetch(tokenOk(TOKEN), () => json(403, { code: "insufficient_scope", error: "no" }));
    const client = new Lingara({ clientId: "lgr_cid_unit", clientSecret: SECRET, fetch: fake.fetch });
    const failure = await client.getUsage().catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    const source = client.tokenSource as ClientCredentials;
    expect(source.exposeToken()).toBe(TOKEN);

    const leaky = new TypeError(`fetch failed for Bearer ${TOKEN}`, { cause: new Error(`secret ${SECRET} echoed`) });
    const transport = transportFailure(leaky, undefined, "fetch", [SECRET, TOKEN]);
    expect(transport).toBeInstanceOf(TransportError);

    for (const value of [client, source, failure, transport]) {
      for (const text of renderings(value)) {
        expect(text).not.toContain(SECRET);
        expect(text).not.toContain(TOKEN);
      }
    }
    expect(inspect(client)).toContain("[REDACTED]");
    expect(inspect(client)).toContain("lgr_cid_unit");
    expect(JSON.stringify(source)).toContain("[REDACTED]");
    expect(inspect((transport as TransportError).cause, { depth: 10 })).toContain("[REDACTED]");
    // The runtime's own error is left untouched.
    expect(leaky.message).toContain(TOKEN);
  });

  /** 29.9.26o AC22: each runtime's recorded failure shape maps to its kind. */
  it("transport failures map on every runtime", () => {
    for (const shape of TRANSPORT_SHAPES) {
      expect(transportKind(shape.error, shape.phase), `${shape.runtime}: ${shape.what}`).toBe(shape.kind);
    }
    expect(new Set(TRANSPORT_SHAPES.map((s) => s.runtime))).toEqual(new Set(["node", "deno", "bun"]));
  });

  /** 29.9.26o AC26: an error from a second copy of the classes is instanceof this copy's. */
  it("instanceof holds across two copies", async () => {
    vi.resetModules();
    const copy = await import("../errors.js");
    expect(copy.ApiError).not.toBe(ApiError);
    const theirs = new copy.ApiError({ status: 400, code: "invalid_request", message: "no" });
    expect(theirs).toBeInstanceOf(ApiError);
    expect(theirs).toBeInstanceOf(LingaraError);
    expect(theirs).not.toBeInstanceOf(OAuthError);
    expect(new ApiError({ status: 400, code: "x", message: "y" })).toBeInstanceOf(copy.LingaraError);
    expect(new copy.TransportError("reset")).toBeInstanceOf(TransportError);
    expect(new Error("plain")).not.toBeInstanceOf(LingaraError);
  });

  it("maps responses to their variant", async () => {
    const maintenance = await errorFromResponse(new Response("Service is under maintenance.", { status: 503 }), { endpoint: "token" });
    expect(maintenance).toBeInstanceOf(MaintenanceError);
    expect((maintenance as MaintenanceError).body).toBe("Service is under maintenance.");

    const big = await errorFromResponse(new Response("x".repeat(5000), { status: 503 }), { endpoint: "v1" });
    expect((big as MaintenanceError).body).toHaveLength(1024);

    const envelope = await errorFromResponse(json(429, { code: "rate_limited", error: "Slow." }), { endpoint: "v1", retryAfter: 90, servedVersion: "2026-09-a-b" });
    expect(envelope).toMatchObject({ status: 429, code: "rate_limited", message: "Slow.", retryAfter: 90, servedVersion: "2026-09-a-b" });

    const proxy = await errorFromResponse(new Response("<html>", { status: 502, headers: { "content-type": "text/html" } }), { endpoint: "v1" });
    expect(proxy).toMatchObject({ status: 502, code: "http_502" });

    const oauth = await errorFromResponse(json(400, { error: "invalid_scope", error_description: "nope" }), { endpoint: "token" });
    expect(oauth).toMatchObject({ status: 400, error: "invalid_scope", description: "nope" });

    const oauthProxy = await errorFromResponse(new Response("", { status: 500 }), { endpoint: "token" });
    expect(oauthProxy).toBeInstanceOf(OAuthError);
    expect(oauthProxy).toMatchObject({ error: "http_500" });
    expect((oauthProxy as OAuthError).description).toBeUndefined();

    const jsonUnavailable = await errorFromResponse(json(503, { code: "unavailable", error: "Later." }), { endpoint: "v1" });
    expect(jsonUnavailable).toBeInstanceOf(ApiError);
  });

  it("the caller's abort is never wrapped", () => {
    const ac = new AbortController();
    const reason = new Error("mine");
    ac.abort(reason);
    expect(transportFailure(new TypeError("fetch failed"), ac.signal, "fetch", [])).toBe(reason);
  });
});
