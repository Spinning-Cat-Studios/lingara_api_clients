// One `fetch`, with its failure turned into a TransportError or the
// signal's own reason. Shared by the client and the token exchange.

import { TransportError, redactCause, transportKind } from "./errors.js";

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface FetchOnce {
  fetch: FetchLike;
  url: string;
  init: RequestInit;
  /** The strings a cause must never carry: the secret, the token. */
  secrets: readonly (string | undefined)[];
}

/** Sends one request; a failure is mapped by `transportFailure`. */
export async function fetchOnce(req: FetchOnce): Promise<Response> {
  try {
    return await req.fetch(req.url, req.init);
  } catch (err) {
    throw transportFailure(err, req.init.signal ?? undefined, "fetch", req.secrets);
  }
}

const UNRESERVED = /^[A-Za-z0-9\-._~]$/;

/**
 * One path parameter as one path segment (CONTRACT.md, Protocol facts; ADR
 * 1.10.26w D5): UTF-8, and every byte outside RFC 3986's unreserved set as
 * upper-case `%XX`, `/` included. Never split, trimmed or normalised.
 * `encodeURIComponent` is not enough: it leaves `! ' ( ) *` as they are.
 */
export function encodeSegment(value: string): string {
  let out = "";
  for (const byte of new TextEncoder().encode(value)) {
    const char = String.fromCharCode(byte);
    out += UNRESERVED.test(char) ? char : `%${byte.toString(16).toUpperCase().padStart(2, "0")}`;
  }
  return out;
}

/**
 * What a failed fetch or body read becomes: the signal's reason when the
 * signal aborted (the library never wraps a cancellation), otherwise a
 * TransportError of the mapped kind with a redacted cause.
 */
export function transportFailure(
  err: unknown,
  signal: AbortSignal | undefined,
  phase: "fetch" | "body",
  secrets: readonly (string | undefined)[],
): unknown {
  if (signal?.aborted) return signal.reason;
  if (err instanceof TransportError) return err;
  return new TransportError(transportKind(err, phase), redactCause(err, secrets));
}
