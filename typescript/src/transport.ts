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
