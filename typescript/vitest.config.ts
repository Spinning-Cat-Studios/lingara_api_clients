import { readFileSync } from "node:fs";
import { defineConfig } from "vitest/config";

// The same constant tsup bakes into the bundle, so the unit tests see the
// real version.
const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8")) as { version: string };

export default defineConfig({
  define: { __LINGARA_VERSION__: JSON.stringify(pkg.version) },
  test: {
    environment: "node",
    include: ["src/__tests__/**/*.test.ts"],
  },
});
