import { afterEach, describe, expect, it } from "vitest";

import { Lingara } from "../client.js";
import { LingaraError } from "../errors.js";
import { STREAMS } from "../generated/streams.js";
import { EventStream } from "../stream.js";
import { LIBRARY_VERSION, runtimeToken } from "../userAgent.js";
import { fakeFetch, json, sse, tokenOk } from "./fixtures/fakeFetch.js";

// CONTRACT.md K6's pattern.
const UA = /^lingara-(typescript|rust|go|java|kotlin|ruby|php)\/(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? \([\x20-\x28\x2A-\x7E]+\)( .+)?$/;

const CREDS = { clientId: "lgr_cid_unit", clientSecret: "lgr_cs_unit" };

afterEach(() => {
  delete (globalThis as { document?: unknown }).document;
});

describe("Lingara", () => {
  /** 29.9.26o AC12: a client secret where a DOM exists is refused. */
  it("refuses a secret in a browser", () => {
    (globalThis as { document?: unknown }).document = {};
    expect(() => new Lingara(CREDS)).toThrow(LingaraError);
    expect(() => new Lingara(CREDS)).toThrow(/browser/);
    // A credential-free client is not refused.
    expect(() => new Lingara()).not.toThrow();
  });

  /** 29.9.26o AC13: the User-Agent matches K6's pattern, carries the version, and a suffix follows it. */
  it("user agent leads with the library token", async () => {
    const fake = fakeFetch(tokenOk("t"), () => json(200, { allowance: [] }));
    const client = new Lingara({ ...CREDS, userAgentSuffix: "kanji-quest/2.1", fetch: fake.fetch });
    await client.getUsage();
    const expected = `lingara-typescript/${LIBRARY_VERSION} (${runtimeToken()}) kanji-quest/2.1`;
    for (const call of fake.calls) {
      const ua = call.headers.get("user-agent")!;
      expect(ua).toMatch(UA);
      expect(ua).toBe(expected);
    }
    expect(runtimeToken()).toMatch(/^node\/\d+\.\d+\.\d+$/);
    expect(runtimeToken({ Bun: { version: "1.2.3" }, process: { versions: { node: "22.0.0" } } })).toBe("bun/1.2.3");
    expect(runtimeToken({ Deno: { version: { deno: "2.1.0" } }, process: { versions: { node: "22.0.0" } } })).toBe("deno/2.1.0");
    expect(runtimeToken({})).toBe("unknown");
  });

  /** 29.9.26o AC17: servedVersion is exposed from the echo and JSON.stringify sees the plain body. */
  it("served version is exposed and not serialised", async () => {
    const body = { allowance: [{ feature: "vocab", window: "daily", limit: 50, used: 1, remaining: 49 }] };
    const fake = fakeFetch(tokenOk("t"), () => json(200, body, { "lingara-version": "2026-09-knowing-tenpounder" }));
    const usage = await new Lingara({ ...CREDS, fetch: fake.fetch }).getUsage();
    expect(usage.servedVersion).toBe("2026-09-knowing-tenpounder");
    expect(JSON.stringify(usage)).toBe(JSON.stringify(body));
    expect(Object.keys(usage)).toEqual(["allowance"]);
  });

  it("a credential-free client calls the three public operations with no token", async () => {
    const fake = fakeFetch(
      () => json(200, { versions: [] }),
      () => json(200, { id: "2026-09-a-b" }),
      () => json(200, { openapi: "3.2.0" }),
    );
    const client = new Lingara({ fetch: fake.fetch, baseUrl: "http://localhost:1/" });
    await client.listApiVersions();
    await client.getApiVersion({ id: "2026-09-a-b" });
    await client.getOpenApiDocument();
    expect(fake.calls.map((c) => c.url)).toEqual([
      "http://localhost:1/v1/versions",
      "http://localhost:1/v1/versions/2026-09-a-b",
      "http://localhost:1/v1/openapi.json",
    ]);
    expect(fake.calls.every((c) => c.headers.get("authorization") === null)).toBe(true);
    await expect(client.getUsage()).rejects.toBeInstanceOf(LingaraError);
  });

  it("stream method names are the spec's stream operations, both ways", () => {
    // Every method that returns an EventStream is a stream operation, and
    // every stream operation has one. A stream starts lazily, so calling
    // each method sends nothing; the JSON ones reject on the empty script.
    const client = new Lingara({ ...CREDS, fetch: fakeFetch().fetch });
    const methods = Object.getOwnPropertyNames(Lingara.prototype).filter((m) => !["constructor", "tokenSource", "toJSON", "toString"].includes(m));
    const streaming = methods.filter((m) => {
      const result = (client as unknown as Record<string, (arg: unknown) => unknown>)[m]!({ id: "x" });
      if (result instanceof Promise) result.catch(() => undefined);
      return result instanceof EventStream;
    });
    expect(streaming.sort()).toEqual(Object.keys(STREAMS).sort());
  });

  it("pins Lingara-Version on /v1 only, and refuses bad options", async () => {
    const fake = fakeFetch(tokenOk("t"), sse(["event: delta\ndata: {\"text\":\"hi\"}\n\nevent: done\ndata: {}\n\n"]));
    const client = new Lingara({ ...CREDS, version: "2026-09-knowing-tenpounder", fetch: fake.fetch });
    const events = [];
    for await (const ev of client.sendTutorMessage({ message: "你好", history: [], source_lang: "en", target_lang: "zh" })) events.push(ev);
    expect(events).toEqual([{ event: "delta", data: { text: "hi" } }]);
    expect(fake.calls[0]!.headers.get("lingara-version")).toBeNull();
    expect(fake.calls[1]!.headers.get("lingara-version")).toBe("2026-09-knowing-tenpounder");
    expect(fake.calls[1]!.headers.get("accept")).toBe("text/event-stream");

    expect(() => new Lingara({ version: "" })).toThrow(LingaraError);
    const tokenSource = { token: async () => "t", invalidate: () => undefined };
    expect(() => new Lingara({ ...CREDS, tokenSource })).toThrow(LingaraError);
  });
});
