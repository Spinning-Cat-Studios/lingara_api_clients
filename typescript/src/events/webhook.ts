// The webhook verifier (ADR 30.9.26aa D4; CONTRACT.md appendix W): Standard
// Webhooks' HMAC-SHA256 scheme keyed on `lgr_whsec_` secrets, native on
// SubtleCrypto so the package keeps no runtime dependency. It checks the
// raw body before anything parses it.

import { LingaraError, REDACTED } from "../errors.js";
import { parseEvent, type Event } from "../generated/events.js";
import { systemClock, type Clock } from "../seams.js";

export type WebhookVerificationReason =
  | "missing_header"
  | "malformed_header"
  | "timestamp_too_old"
  | "timestamp_too_new"
  | "no_matching_signature"
  | "malformed_payload";

const MESSAGES: Record<WebhookVerificationReason, string> = {
  missing_header: "a webhook-id, webhook-timestamp or webhook-signature header is missing",
  malformed_header: "webhook-timestamp is not a whole number of seconds",
  timestamp_too_old: "webhook-timestamp is more than 300 s old",
  timestamp_too_new: "webhook-timestamp is more than 300 s in the future",
  no_matching_signature: "no signature matches the body",
  malformed_payload: "the signed body is not a Lingara event",
};

const BRAND = Symbol.for("lingara.webhook.WebhookVerificationError");

/**
 * A webhook that failed verification. Deliberately not a `LingaraError`: a
 * catch around API calls must not also swallow a forged delivery. The
 * message never carries a secret, a signature or the body.
 */
export class WebhookVerificationError extends Error {
  static override [Symbol.hasInstance](value: unknown): boolean {
    return typeof value === "object" && value !== null && (value as Record<symbol, unknown>)[BRAND] === true;
  }

  readonly reason: WebhookVerificationReason;

  constructor(reason: WebhookVerificationReason) {
    super(MESSAGES[reason]);
    this.name = "WebhookVerificationError";
    this.reason = reason;
    Object.defineProperty(this, BRAND, { value: true, enumerable: false });
  }
}

/** A Fetch `Headers`, or a plain map such as Node's `req.headers`. */
export type WebhookHeaders = Headers | Readonly<Record<string, string | readonly string[] | undefined>>;

export interface WebhookOptions {
  /** A testing seam: epoch milliseconds. */
  clock?: Clock;
}

const PREFIX = "lgr_whsec_";
const MIN_KEY_BYTES = 24;
const SIGNATURE_BYTES = 32;
const TOLERANCE_S = 300;
// Matched before decoding: `atob` accepts missing padding and whitespace.
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const DIGITS = /^[0-9]+$/;

function strictBase64(text: string): Uint8Array<ArrayBuffer> | undefined {
  if (!BASE64.test(text) || text.length % 4 !== 0) return undefined;
  try {
    return Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
  } catch {
    return undefined;
  }
}

function keyOf(secret: string): Uint8Array<ArrayBuffer> {
  const key = secret.startsWith(PREFIX) ? strictBase64(secret.slice(PREFIX.length)) : undefined;
  if (key === undefined || key.length < MIN_KEY_BYTES) {
    throw new LingaraError("a webhook secret is lgr_whsec_ followed by padded base64 of at least 24 bytes");
  }
  return key;
}

function header(headers: WebhookHeaders, name: string): string | undefined {
  if (isFetchHeaders(headers)) return headers.get(name) ?? undefined;
  const entry = Object.entries(headers).find(([k]) => k.toLowerCase() === name);
  const value = entry?.[1];
  return typeof value === "string" || value === undefined ? value : value.join(" ");
}

// Duck-typed, so a `Headers` from another realm or framework still reads.
function isFetchHeaders(headers: WebhookHeaders): headers is Headers {
  return typeof (headers as Headers).get === "function";
}

/** The constant-time comparison: every byte is read, whatever differs. */
function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== SIGNATURE_BYTES || b.length !== SIGNATURE_BYTES) return false;
  let diff = 0;
  for (let i = 0; i < SIGNATURE_BYTES; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

/** Each `v1,<base64>` element; other versions and undecodable ones skipped. */
function v1Signatures(list: string): Uint8Array[] {
  return list
    .split(" ")
    .filter((e) => e.startsWith("v1,"))
    .map((e) => strictBase64(e.slice(3)))
    .filter((s): s is Uint8Array<ArrayBuffer> => s !== undefined);
}

function signedContent(id: string, timestamp: string, body: string | Uint8Array): Uint8Array<ArrayBuffer> {
  const encoder = new TextEncoder();
  const head = encoder.encode(`${id}.${timestamp}.`);
  const rest = typeof body === "string" ? encoder.encode(body) : body;
  const out = new Uint8Array(head.length + rest.length);
  out.set(head);
  out.set(rest, head.length);
  return out;
}

interface Signed {
  id: string;
  timestamp: string;
  signature: string;
}

/**
 * Verifies Lingara webhooks. Pass the raw body exactly as received, never a
 * parsed object. Two secrets verify during a rotation.
 */
export class Webhook {
  readonly #secrets: Uint8Array<ArrayBuffer>[];
  readonly #clock: Clock;
  #keys: Promise<CryptoKey[]> | undefined;

  constructor(secret: string | readonly string[], options: WebhookOptions = {}) {
    const secrets = typeof secret === "string" ? [secret] : secret;
    if (secrets.length === 0) throw new LingaraError("a Webhook needs at least one secret");
    this.#secrets = secrets.map(keyOf);
    this.#clock = options.clock ?? systemClock;
  }

  /** Signature checks, then the body parsed into an `Event` whose `id` is `webhook-id`. */
  async verify(body: string | Uint8Array, headers: WebhookHeaders): Promise<Event> {
    const signed = await this.#check(body, headers);
    let event: Event;
    try {
      event = parseEvent(typeof body === "string" ? body : new TextDecoder("utf-8", { fatal: true }).decode(body));
    } catch {
      throw new WebhookVerificationError("malformed_payload");
    }
    if (event.id !== signed.id) throw new WebhookVerificationError("malformed_payload");
    return event;
  }

  /** The signature checks alone, for a signed body that is not an event (an app-kit request). */
  async verifySignature(body: string | Uint8Array, headers: WebhookHeaders): Promise<void> {
    await this.#check(body, headers);
  }

  toJSON(): Record<string, unknown> {
    return { secrets: this.#secrets.map(() => REDACTED) };
  }

  toString(): string {
    return `Webhook ${JSON.stringify(this.toJSON())}`;
  }

  async #check(body: string | Uint8Array, headers: WebhookHeaders): Promise<Signed> {
    const signed = this.#headers(headers);
    const content = signedContent(signed.id, signed.timestamp, body);
    const candidates = v1Signatures(signed.signature);
    const keys = await this.#cryptoKeys();
    const expected = await Promise.all(keys.map((k) => globalThis.crypto.subtle.sign("HMAC", k, content)));
    const match = expected.some((e) => candidates.some((c) => sameBytes(new Uint8Array(e), c)));
    if (!match) throw new WebhookVerificationError("no_matching_signature");
    return signed;
  }

  #headers(headers: WebhookHeaders): Signed {
    const id = header(headers, "webhook-id");
    const timestamp = header(headers, "webhook-timestamp");
    const signature = header(headers, "webhook-signature");
    if (id === undefined || timestamp === undefined || signature === undefined) throw new WebhookVerificationError("missing_header");
    if (!DIGITS.test(timestamp)) throw new WebhookVerificationError("malformed_header");
    const age = this.#clock.now() / 1000 - Number(timestamp);
    if (age > TOLERANCE_S) throw new WebhookVerificationError("timestamp_too_old");
    if (age < -TOLERANCE_S) throw new WebhookVerificationError("timestamp_too_new");
    return { id, timestamp, signature };
  }

  #cryptoKeys(): Promise<CryptoKey[]> {
    const algorithm = { name: "HMAC", hash: "SHA-256" };
    this.#keys ??= Promise.all(this.#secrets.map((k) => globalThis.crypto.subtle.importKey("raw", k, algorithm, false, ["sign"])));
    return this.#keys;
  }
}
