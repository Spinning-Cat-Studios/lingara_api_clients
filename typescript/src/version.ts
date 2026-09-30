// K2: the served version and the deprecation hook (CONTRACT.md K2). The pin
// itself is one header the client adds; this reads what came back.

import { GENERATED_FOR_VERSION } from "./generated/specVersion.js";

/** What a response under a deprecated version says about it. */
export interface DeprecationNotice {
  /** The `Lingara-Version` echo. */
  version?: string;
  /** `Deprecation`, parsed from `@<unix seconds>`; absent when unparseable. */
  deprecatedAt?: Date;
  /** `Sunset`, parsed from an IMF-fixdate; absent when unparseable or missing. */
  sunsetAt?: Date;
  /** `Link`: the raw value, and its target resolved against the request URL. */
  link?: { raw: string; url?: URL };
  /** The raw `Deprecation` header. */
  deprecation: string;
  /** The raw `Sunset` header. */
  sunset?: string;
}

export type DeprecationHook = (notice: DeprecationNotice) => void;

const UNIX_SECONDS = /^@(-?\d+)$/;
const IMF_FIXDATE = /^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} GMT$/;
const LINK_TARGET = /^\s*<([^>]*)>/;

export function parseDeprecation(value: string): Date | undefined {
  const match = UNIX_SECONDS.exec(value.trim());
  return match ? new Date(Number(match[1]) * 1000) : undefined;
}

export function parseSunset(value: string): Date | undefined {
  const trimmed = value.trim();
  if (!IMF_FIXDATE.test(trimmed)) return undefined;
  const at = Date.parse(trimmed);
  return Number.isNaN(at) ? undefined : new Date(at);
}

export function parseLink(raw: string, requestUrl: string): { raw: string; url?: URL } {
  const target = LINK_TARGET.exec(raw)?.[1];
  if (target === undefined) return { raw };
  try {
    return { raw, url: new URL(target, requestUrl) };
  } catch {
    return { raw };
  }
}

/** The notice for a response, or `undefined` when it carries no `Deprecation`. */
export function deprecationNotice(headers: Headers, requestUrl: string): DeprecationNotice | undefined {
  const deprecation = headers.get("deprecation");
  if (deprecation === null) return undefined;
  const notice: DeprecationNotice = { deprecation };
  const version = headers.get("lingara-version");
  const sunset = headers.get("sunset");
  const link = headers.get("link");
  const deprecatedAt = parseDeprecation(deprecation);
  if (version !== null) notice.version = version;
  if (deprecatedAt) notice.deprecatedAt = deprecatedAt;
  if (sunset !== null) {
    notice.sunset = sunset;
    const sunsetAt = parseSunset(sunset);
    if (sunsetAt) notice.sunsetAt = sunsetAt;
  }
  if (link !== null) notice.link = parseLink(link, requestUrl);
  return notice;
}

/**
 * Per client: reads the served version off each response and reports a
 * deprecation once per response to the hook, or, with no hook, warns once
 * per version id. Separately, warns once per served id that is not the
 * version the types were generated from (ADR 30.9.26a §4).
 */
export class VersionObserver {
  readonly #hook: DeprecationHook | undefined;
  readonly #warned = new Set<string>();
  // Its own set: sharing `#warned` would let a version that is both
  // deprecated and mismatched warn only once in total.
  readonly #mismatched = new Set<string>();

  constructor(hook?: DeprecationHook) {
    this.#hook = hook;
  }

  /** Returns the `Lingara-Version` echo, after reporting any deprecation or mismatch. */
  observe(res: Response, requestUrl: string): string | undefined {
    const notice = deprecationNotice(res.headers, requestUrl);
    if (notice) this.#report(notice);
    const served = res.headers.get("lingara-version") ?? undefined;
    if (served !== undefined) this.#checkGenerated(served);
    return served;
  }

  #checkGenerated(served: string): void {
    if (served === GENERATED_FOR_VERSION || this.#mismatched.has(served)) return;
    this.#mismatched.add(served);
    console.warn(
      `Lingara API version ${served} served this response, but this library's types were generated for ` +
        `${GENERATED_FOR_VERSION}; response shapes may differ. Pin the OAuth client to ${GENERATED_FOR_VERSION} ` +
        `or upgrade the library.`,
    );
  }

  #report(notice: DeprecationNotice): void {
    if (!this.#hook) {
      const id = notice.version ?? "";
      if (this.#warned.has(id)) return;
      this.#warned.add(id);
      const sunset = notice.sunset ? `; sunset ${notice.sunset}` : "";
      console.warn(`Lingara API version ${id || "(unnamed)"} is deprecated${sunset}. See GET /v1/versions.`);
      return;
    }
    try {
      this.#hook(notice);
    } catch (err) {
      console.debug("Lingara deprecation hook threw", err);
    }
  }
}
