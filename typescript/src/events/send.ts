// `sendEvent`'s request (ADR 30.9.26aa D8; CONTRACT.md K4's
// `Idempotency-Key` rule): the `{type, data}` body and the one key every
// attempt of a call carries.

import type { InboundEvent } from "../generated/events.js";
import type { CallOptions } from "../options.js";

export interface SendEventOptions extends CallOptions {
  /**
   * Sent unchanged as `Idempotency-Key`. Supply your own when you may resend
   * after a crash: a generated key is gone once the call returns. A reused
   * key returns the first answer, whatever the body.
   */
  idempotencyKey?: string;
}

export interface SendEventRequest {
  body: { type: string; data: unknown };
  headers: { "idempotency-key": string };
}

/**
 * The body and headers of one `sendEvent` call. Without a caller's key, a
 * UUIDv4 from the platform CSPRNG, made once here, before the first attempt,
 * so K4's retries all send the same one.
 */
export function sendEventRequest(event: InboundEvent, options: SendEventOptions): SendEventRequest {
  const key = options.idempotencyKey ?? globalThis.crypto.randomUUID();
  return { body: { type: event.type, data: event.data }, headers: { "idempotency-key": key } };
}
