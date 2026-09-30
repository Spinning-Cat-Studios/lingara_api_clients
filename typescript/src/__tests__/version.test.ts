import { afterEach, describe, expect, it, vi, type MockInstance } from "vitest";

import { Lingara } from "../client.js";
import { GENERATED_FOR_VERSION } from "../generated/specVersion.js";
import { VersionObserver, deprecationNotice, parseLink, type DeprecationNotice } from "../version.js";
import { fakeFetch, json } from "./fixtures/fakeFetch.js";

const LINK = '</v1/versions/2026-09-affable-cat>; rel="deprecation"; type="application/json"';
const REQUEST_URL = "https://api.example.test/v1/usage";

function deprecated(headers: Record<string, string>): Response {
  return new Response("{}", { headers: { "lingara-version": "2026-09-affable-cat", ...headers } });
}

/** The warnings logged so far whose text contains `marker`. */
function warnings(warn: MockInstance<typeof console.warn>, marker: string): string[] {
  return warn.mock.calls.map(([message]) => String(message)).filter((message) => message.includes(marker));
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("versions", () => {
  /** 29.9.26o AC25: the hook gets parsed dates and a resolved Link; bad headers stay raw; a throwing hook is swallowed; no hook warns once per id. */
  it("deprecation hook parsing and warn once", () => {
    const calls: DeprecationNotice[] = [];
    const observer = new VersionObserver((n) => calls.push(n));
    const served = observer.observe(
      deprecated({ deprecation: "@1790812800", sunset: "Mon, 01 Mar 2027 00:00:00 GMT", link: LINK }),
      REQUEST_URL,
    );
    expect(served).toBe("2026-09-affable-cat");
    expect(calls).toHaveLength(1);
    const notice = calls[0]!;
    expect(notice.version).toBe("2026-09-affable-cat");
    expect(notice.deprecatedAt?.getTime()).toBe(1790812800_000);
    expect(notice.sunsetAt?.getTime()).toBe(1803859200_000);
    expect(notice.link?.raw).toBe(LINK);
    expect(notice.link?.url?.href).toBe("https://api.example.test/v1/versions/2026-09-affable-cat");

    const raw = deprecationNotice(deprecated({ deprecation: "last Tuesday", sunset: "soon" }).headers, REQUEST_URL)!;
    expect(raw.deprecation).toBe("last Tuesday");
    expect(raw.sunset).toBe("soon");
    expect(raw.deprecatedAt).toBeUndefined();
    expect(raw.sunsetAt).toBeUndefined();

    const debug = vi.spyOn(console, "debug").mockImplementation(() => undefined);
    const throwing = new VersionObserver(() => {
      throw new Error("hook bug");
    });
    expect(throwing.observe(deprecated({ deprecation: "@1" }), REQUEST_URL)).toBe("2026-09-affable-cat");
    expect(debug).toHaveBeenCalledTimes(1);

    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const silent = new VersionObserver();
    silent.observe(deprecated({ deprecation: "@1" }), REQUEST_URL);
    silent.observe(deprecated({ deprecation: "@1" }), REQUEST_URL);
    silent.observe(new Response("{}", { headers: { "lingara-version": "2026-10-other-one", deprecation: "@1" } }), REQUEST_URL);
    expect(warnings(warn, "is deprecated")).toHaveLength(2);
  });

  /** 30.9.26a AC9: two responses echoing another id log one mismatch warning; an echo of GENERATED_FOR_VERSION, or no echo, logs none; a deprecated and mismatched id logs both warnings; no lingara-version header is sent without a configured version. */
  it("warns once when served another version", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const echo = (version: string, extra: Record<string, string> = {}) =>
      new Response("{}", { headers: { "lingara-version": version, ...extra } });
    const observer = new VersionObserver();

    observer.observe(echo("2026-09-commending-possum"), REQUEST_URL);
    observer.observe(echo("2026-09-commending-possum"), REQUEST_URL);
    expect(warnings(warn, "generated for")).toHaveLength(1);
    expect(warnings(warn, "generated for")[0]).toContain("2026-09-commending-possum");
    expect(warnings(warn, "generated for")[0]).toContain(GENERATED_FOR_VERSION);

    observer.observe(echo(GENERATED_FOR_VERSION), REQUEST_URL);
    observer.observe(new Response("{}"), REQUEST_URL);
    expect(warn).toHaveBeenCalledTimes(1);

    observer.observe(echo("2026-09-affable-cat", { deprecation: "@1" }), REQUEST_URL);
    expect(warnings(warn, "is deprecated")).toHaveLength(1);
    expect(warnings(warn, "generated for")).toHaveLength(2);

    const fake = fakeFetch(() => json(200, { current: null, development: null, versions: [] }, { "lingara-version": "2026-09-commending-possum" }));
    await new Lingara({ fetch: fake.fetch }).listApiVersions();
    expect(fake.calls[0]!.headers.get("lingara-version")).toBeNull();
  });

  it("a response with no Deprecation calls nothing", () => {
    const hook = vi.fn();
    const observer = new VersionObserver(hook);
    expect(observer.observe(new Response("{}"), REQUEST_URL)).toBeUndefined();
    expect(hook).not.toHaveBeenCalled();
  });

  it("an unresolvable Link keeps its raw value only", () => {
    expect(parseLink("no brackets", REQUEST_URL)).toEqual({ raw: "no brackets" });
    expect(parseLink("<http://[bad>", REQUEST_URL)).toEqual({ raw: "<http://[bad>" });
  });
});
