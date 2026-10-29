// The request pipeline's private shapes, kept out of client.ts so the client
// stays under its line budget (ADR 22.10.26t D5). Nothing here is exported
// from the package.

import type { operations } from "./generated/schema.js";

export type JsonOk<Op extends keyof operations> = operations[Op]["responses"] extends { 200: { content: { "application/json": infer T } } } ? T : never;

/** A bodiless answer's result: only the `Lingara-Version` echo. */
export type NoContent = { readonly servedVersion?: string };

export interface Send {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  path: string;
  body?: unknown;
  accept: string;
  needsToken: boolean;
  headers?: Record<string, string> | undefined;
  /** `false` bypasses K4's attempt loop: the tail counts its own (K5a). */
  retries?: boolean;
  /** A non-2xx status the caller reads itself rather than raises (an export's `304`, ADR 29.10.26c D6). */
  allow?: number;
  signal?: AbortSignal | undefined;
}

/** A response its caller reads itself, with the `Lingara-Version` echo already observed. */
export interface RawResponse {
  response: Response;
  servedVersion: string | undefined;
}

/** A stream request's parts beyond its route. */
export interface StreamInput {
  body?: unknown;
  id?: string;
  query?: string;
  headers?: Record<string, string>;
  retries?: boolean;
}
