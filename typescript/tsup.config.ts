import { readFileSync } from "node:fs";
import { defineConfig } from "tsup";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as { version: string };
const define = { __LINGARA_VERSION__: JSON.stringify(pkg.version) };

export default defineConfig([
  // The package: ESM and CJS, each with its own declaration file.
  {
    entry: { index: "src/index.ts" },
    format: ["esm", "cjs"],
    dts: true,
    target: "es2022",
    platform: "neutral",
    clean: true,
    sourcemap: false,
    define,
    // The declarations are built in a worker this function never reaches, so
    // they take tsup's defaults: `.d.ts` (ESM, as the package is
    // `"type": "module"`) and `.d.cts`.
    outExtension: ({ format }) => ({ js: format === "esm" ? ".mjs" : ".cjs" }),
  },
  // The conformance harness, built against the built package rather than
  // `src/`, so conformance tests the artefact that ships.
  {
    entry: { harness: "conformance/harness.ts" },
    outDir: "conformance/dist",
    format: ["esm"],
    target: "es2022",
    platform: "node",
    clean: true,
    outExtension: () => ({ js: ".mjs" }),
    // `@lingara/api` stays an import, rewritten to the built bundle's path
    // relative to conformance/dist/: the same on Node, Deno and Bun, and
    // unchanged if the package name ever falls back.
    esbuildPlugins: [
      {
        name: "lingara-api-alias",
        setup(build) {
          build.onResolve({ filter: /^@lingara\/api$/ }, () => ({ path: "../../dist/index.mjs", external: true }));
        },
      },
    ],
  },
]);
