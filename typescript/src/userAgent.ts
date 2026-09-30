// K6: `User-Agent: lingara-typescript/<version> (<runtime>)` on every
// request, `/oauth/token` included (CONTRACT.md K6).

/** The package version, baked in by the build (tsup's and vitest's `define`). */
declare const __LINGARA_VERSION__: string;

export const LIBRARY_VERSION: string = __LINGARA_VERSION__;

// Just the fields read here, declared locally, so the published types name
// no `NodeJS.*` type and Deno and Bun users keep type-checking.
interface RuntimeGlobals {
  Bun?: { version?: unknown };
  Deno?: { version?: { deno?: unknown } };
  process?: { versions?: { node?: unknown } };
}

/**
 * `bun/<v>`, `deno/<v>` or `node/<v>`, checked in that order because Bun
 * and Deno both expose `process.versions.node`; otherwise `unknown`.
 */
export function runtimeToken(g: RuntimeGlobals = globalThis as RuntimeGlobals): string {
  const bun = g.Bun?.version;
  if (typeof bun === "string") return clean(`bun/${bun}`);
  const deno = g.Deno?.version?.deno;
  if (typeof deno === "string") return clean(`deno/${deno}`);
  const node = g.process?.versions?.node;
  if (typeof node === "string") return clean(`node/${node}`);
  return "unknown";
}

/** Visible ASCII only, with no `)`. */
function clean(value: string): string {
  return value.replace(/[^\x21-\x28\x2A-\x7E]/g, "");
}

/** The full header value; a caller's suffix follows after one space. */
export function userAgent(suffix?: string): string {
  const own = `lingara-typescript/${LIBRARY_VERSION} (${runtimeToken()})`;
  return suffix ? `${own} ${suffix}` : own;
}
