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
// Events (ADR 30.9.26aa): the union and its parser, the verifier, the feed,
// the tail and sendEvent's options.
export { InboundEvent, UnknownEvent, parseEvent } from "./generated/events.js";
export type * from "./generated/events.js";
export { Webhook, WebhookVerificationError } from "./events/webhook.js";
export type { WebhookHeaders, WebhookOptions, WebhookVerificationReason } from "./events/webhook.js";
export { EventFeed } from "./events/feed.js";
export type { EventsParams, ListEventsParams } from "./events/feed.js";
export { EventTail } from "./events/tail.js";
export type { SendEventOptions } from "./events/send.js";
// Embedding (ADR 1.10.26w): the token createEmbedToken mints.
export { MintedToken } from "./embed.js";
