// Embedding Lingara (ADR 1.10.26w D3): the token a server mints for one
// player. A generator cannot know that a response field is a credential, so
// `createEmbedToken` returns this hand-written class rather than the
// generated `EmbedToken`, and the `lgr_et_` value renders redacted like every
// other token (CONTRACT.md K1).

import { INSPECT, REDACTED, TransportError } from "./errors.js";
import type { EmbedToken } from "./models.js";

/** A player's embed token, as `createEmbedToken` returns it. */
export class MintedToken {
  readonly #token: string;
  /** When the token stops working (RFC 3339, the server's string). Lingara never refreshes it: mint again. */
  readonly expiresAt: string;
  /** The token's lifetime in seconds, counted from the answer. Prefer it on a device whose clock cannot be trusted. */
  readonly expiresIn: number;
  /** The player's pairwise `lgr_sub_`, stable across mints. Store it beside the player: it is how an event names them. */
  readonly subject: string;
  /** The scopes granted: every handable scope the client holds when the request named none. */
  readonly scopes: readonly string[];
  /** Whether the player has linked a Lingara account. */
  readonly accountLinked: boolean;

  /** Built by `createEmbedToken` from a checked answer; see `mintedToken`. */
  constructor(answer: EmbedToken) {
    this.#token = answer.token;
    this.expiresAt = answer.expires_at;
    this.expiresIn = answer.expires_in;
    this.subject = answer.subject;
    this.scopes = Object.freeze([...answer.scopes]);
    this.accountLinked = answer.account_linked;
  }

  /** The `lgr_et_` bearer token, raw, to hand to the player's device. The one accessor that does not redact. */
  exposeToken(): string {
    return this.#token;
  }

  toJSON(): Record<string, unknown> {
    const { expiresAt, expiresIn, subject, scopes, accountLinked } = this;
    return { token: REDACTED, expiresAt, expiresIn, subject, scopes, accountLinked };
  }

  [INSPECT](): string {
    return `MintedToken ${JSON.stringify(this.toJSON())}`;
  }

  toString(): string {
    return this[INSPECT]();
  }
}

// Each of the mint's six fields and the check its JSON type must pass. The
// types are erased at runtime, so a decoded body is checked here by hand.
const FIELDS: Record<keyof EmbedToken, (value: unknown) => boolean> = {
  token: (v) => typeof v === "string" && v.startsWith("lgr_et_"),
  expires_at: (v) => typeof v === "string",
  expires_in: (v) => Number.isInteger(v),
  subject: (v) => typeof v === "string",
  scopes: (v) => Array.isArray(v) && v.every((s) => typeof s === "string"),
  account_linked: (v) => typeof v === "boolean",
};

/**
 * A `MintedToken` from the mint's decoded answer. Every one of the six
 * fields must be present and of its JSON type, and the token must start
 * `lgr_et_`. A failure is `TransportError{kind: malformed_response}` with no
 * cause, so nothing of the body (the token least of all) can leak through it.
 * The body's non-enumerable `servedVersion` is carried over the same way.
 */
export function mintedToken(body: unknown): MintedToken & { readonly servedVersion?: string } {
  const answer = (typeof body === "object" && body !== null ? body : {}) as Record<string, unknown>;
  const ok = Object.entries(FIELDS).every(([name, check]) => check(answer[name]));
  if (!ok) throw new TransportError("malformed_response");
  const minted = new MintedToken(answer as unknown as EmbedToken);
  const servedVersion = answer["servedVersion"];
  if (servedVersion !== undefined) Object.defineProperty(minted, "servedVersion", { value: servedVersion, enumerable: false });
  return minted;
}
