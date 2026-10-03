import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import { LingaraError } from "../errors.js";
import { Webhook, WebhookVerificationError } from "../events/webhook.js";
import { UnknownEvent } from "../generated/events.js";

// ADR 30.9.26aa D5: the one vector file every library's verifier reads.
interface Vector {
  name: string;
  secrets: string[];
  headers: Record<string, string>;
  body: string;
  now: number;
  expect: { ok: { id: string; type: string; unknown?: boolean } } | { error: string } | { refused: true };
}

const VECTORS = (
  JSON.parse(readFileSync(new URL("../../../conformance/vectors/webhook-signatures.json", import.meta.url), "utf8")) as {
    vectors: Vector[];
  }
).vectors;

const clockAt = (seconds: number) => ({ now: () => seconds * 1000 });

function webhook(v: Vector): Webhook {
  return new Webhook(v.secrets, { clock: clockAt(v.now) });
}

/** What verify (or verifySignature) did: `ok` with the event, or the reason. */
async function outcome<T>(run: () => Promise<T>): Promise<{ ok: T } | { reason: string; error: unknown }> {
  try {
    return { ok: await run() };
  } catch (error) {
    const reason = error instanceof WebhookVerificationError ? error.reason : `not a WebhookVerificationError: ${String(error)}`;
    return { reason, error };
  }
}

// The official Standard Webhooks library, a devDependency only (D4). Loaded
// by name at run time so a missing install fails this test, not the build.
interface Oracle {
  Webhook: new (secret: string) => { sign(msgId: string, timestamp: Date, payload: string): string };
}
const ORACLE = "standardwebhooks";

async function oracle(): Promise<Oracle> {
  try {
    return (await import(/* @vite-ignore */ ORACLE)) as Oracle;
  } catch (cause) {
    throw new Error("the oracle is missing: run npm --prefix typescript install --save-dev standardwebhooks", { cause });
  }
}

const lower = (headers: Record<string, string>) => Object.fromEntries(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));

/** A vector the oracle can sign: secrets the verifier accepts, all three headers, an integer timestamp. */
function signable(v: Vector): boolean {
  const h = lower(v.headers);
  const complete = h["webhook-id"] !== undefined && h["webhook-signature"] !== undefined;
  return !("refused" in v.expect) && complete && /^[0-9]+$/.test(h["webhook-timestamp"] ?? "");
}

describe("Webhook", () => {
  /**
   * 30.9.26aa AC22: every signed vector's signatures agree with the official
   * Standard Webhooks library given the same key (the remainder after
   * `lgr_`, as `whsec_…`): byte-identical `v1,` signatures wherever the
   * vector's signature is meant to match, and none where it is not.
   */
  it("every vector re-signs identically with the official library", async () => {
    const { Webhook: Official } = await oracle();
    const signed = VECTORS.filter(signable);
    expect(signed.length).toBeGreaterThan(0);
    for (const v of signed) {
      const h = lower(v.headers);
      const at = new Date(Number(h["webhook-timestamp"]) * 1000);
      const official = v.secrets.map((s) => new Official(s.slice("lgr_".length)).sign(h["webhook-id"]!, at, v.body));
      const offered = h["webhook-signature"]!.split(" ");
      const shouldMatch = !("error" in v.expect) || v.expect.error !== "no_matching_signature";
      expect(offered.some((sig) => official.includes(sig)), v.name).toBe(shouldMatch);
    }
    for (const v of VECTORS.filter((x) => "ok" in x.expect)) expect(signable(v), `${v.name} is re-signed`).toBe(true);
  });

  /** 30.9.26aa AC23: every D5 vector gives its expected result through verify. */
  it("every shared vector verifies as expected", async () => {
    for (const v of VECTORS) {
      if ("refused" in v.expect) {
        expect(() => webhook(v), v.name).toThrow(LingaraError);
        continue;
      }
      const seen = await outcome(() => webhook(v).verify(v.body, v.headers));
      if ("error" in v.expect) {
        expect(seen, v.name).toMatchObject({ reason: v.expect.error });
        if ("error" in seen) expect(seen.error, v.name).not.toBeInstanceOf(LingaraError);
        continue;
      }
      expect("ok" in seen, `${v.name}: ${"reason" in seen ? seen.reason : ""}`).toBe(true);
      if (!("ok" in seen)) continue;
      expect({ id: seen.ok.id, type: seen.ok.type }, v.name).toEqual({ id: v.expect.ok.id, type: v.expect.ok.type });
      expect(seen.ok instanceof UnknownEvent, v.name).toBe(v.expect.ok.unknown === true);
    }
  });

  /**
   * 30.9.26aa AC45: verifySignature passes every ok vector and every
   * malformed_payload one (their signatures match), and raises each other
   * error vector's own reason.
   */
  it("verifySignature agrees with every shared vector except the envelope check", async () => {
    for (const v of VECTORS) {
      if ("refused" in v.expect) continue;
      const seen = await outcome(() => webhook(v).verifySignature(v.body, v.headers));
      const signatureFails = "error" in v.expect && v.expect.error !== "malformed_payload";
      if (signatureFails) expect(seen, v.name).toMatchObject({ reason: (v.expect as { error: string }).error });
      else expect(seen, v.name).toEqual({ ok: undefined });
    }
  });

  it("accepts Headers, string-array maps and a byte body, and never renders a secret", async () => {
    const v = VECTORS.find((x) => x.name === "valid-single-signature")!;
    const bytes = new TextEncoder().encode(v.body);
    await expect(webhook(v).verify(bytes, new Headers(v.headers))).resolves.toMatchObject({ id: "lgr_evt_conformance1" });
    const listed = Object.fromEntries(Object.entries(v.headers).map(([k, x]) => [k, [x]]));
    await expect(webhook(v).verifySignature(v.body, listed)).resolves.toBeUndefined();
    const rendered = [String(webhook(v)), JSON.stringify(webhook(v))].join("\n");
    expect(rendered).not.toContain(v.secrets[0]!.slice("lgr_whsec_".length));
    expect(() => new Webhook([])).toThrow(LingaraError);
  });
});
