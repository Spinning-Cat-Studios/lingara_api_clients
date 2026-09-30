// @lingara/api: the public surface.

export { Lingara, DEFAULT_BASE_URL } from "./client.js";
export type { CallOptions, LingaraOptions, WithServedVersion } from "./client.js";
export { ClientCredentials, DEFAULT_TOKEN_URL } from "./token.js";
export type { ClientCredentialsOptions, TokenSource } from "./token.js";
export { ApiError, LingaraError, MaintenanceError, OAuthError, TransportError } from "./errors.js";
export type { TransportKind } from "./errors.js";
export { EventStream } from "./stream.js";
export type { Yielded } from "./stream.js";
export type { DeprecationHook, DeprecationNotice } from "./version.js";
export type { Clock, Sleeper } from "./seams.js";
export type { FetchLike } from "./transport.js";
export type * from "./models.js";
export type { components, operations } from "./generated/schema.js";
