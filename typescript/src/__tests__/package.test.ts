import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const pkg = JSON.parse(readFileSync(new URL("../../package.json", import.meta.url), "utf8")) as Record<string, unknown>;
const repoVersion = readFileSync(new URL("../../../VERSION", import.meta.url), "utf8").trim();

describe("package.json", () => {
  /** 29.9.26o AC16: no runtime dependencies of any kind. */
  it("package has no runtime dependencies", () => {
    for (const key of ["dependencies", "peerDependencies", "optionalDependencies"]) {
      const deps = (pkg[key] ?? {}) as Record<string, string>;
      expect(Object.keys(deps), key).toEqual([]);
    }
  });

  it("version equals the repository VERSION, and ships dist alone", () => {
    expect(pkg["version"]).toBe(repoVersion);
    expect(pkg["files"]).toEqual(["dist"]);
    expect(pkg["engines"]).toEqual({ node: ">=22.0.0" });
    expect(pkg["browser"]).toBeUndefined();
  });

  it("the lint budgets are the portfolio's numbers", async () => {
    // A computed specifier: the config is plain JavaScript with no types.
    const config = new URL("../../eslint.config.mjs", import.meta.url).href;
    const { BUDGETS } = (await import(config)) as { BUDGETS: unknown };
    expect(BUDGETS).toEqual({ warnLines: 300, maxLines: 600, maxLinesPerFunction: 50, complexity: 10, maxParams: 5 });
  });
});
