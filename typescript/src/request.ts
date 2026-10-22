// The request pipeline's private shapes, kept out of client.ts so the client
// stays under its line budget (ADR 22.10.26t D5). Nothing here is exported
// from the package.

import type { operations } from "./generated/schema.js";

export type JsonOk<Op extends keyof operations> = operations[Op]["responses"] extends { 200: { content: { "application/json": infer T } } } ? T : never;

/** A bodiless answer's result: only the `Lingara-Version` echo. */
export type NoContent = { readonly servedVersion?: string };

export interface Send {
  method: "GET" | "POST" | "PUT" | "DELETE";
  path: string;
  body?: unknown;
  accept: string;
  needsToken: boolean;
  headers?: Record<string, string> | undefined;
  /** `false` bypasses K4's attempt loop: the tail counts its own (K5a). */
  retries?: boolean;
  signal?: AbortSignal | undefined;
}

/** A stream request's parts beyond its route. */
export interface StreamInput {
  body?: unknown;
  id?: string;
  query?: string;
  headers?: Record<string, string>;
  retries?: boolean;
}
