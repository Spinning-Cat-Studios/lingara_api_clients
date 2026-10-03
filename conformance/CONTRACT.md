# The Lingara client contract

Every official Lingara API library keeps this contract. It fixes the
behaviour, down to the header name, the byte and the second; the spelling of
an API (method, option and class names) is each language's own. A library's
README links this file, and a library that does not meet a SHOULD says so in
its own documentation.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT and MAY are to be read as in
RFC 2119. The cases under [`cases/`](cases/) make the contract executable:
every library runs every case, and a library that cannot pass one does not
ship. [`README.md`](README.md) says how the cases and the harnesses work.

Decided by ADR 29.9.26n. Names below are language-neutral: *client*, *token
source*, *call*, *stream*.

## Protocol facts the contract relies on

These are properties of the Lingara API that the rules below depend on.

- **Operations.** Thirteen. Nine need an access token: `generateVocabulary`
  (scope `vocab:generate`), `createLessonPlan` (`lesson_plans:write`),
  `getLessonPlan` and `streamLessonPlan` (`lesson_plans:read`),
  `sendTutorMessage` (`tutor:converse`), `getUsage` (`usage:read`),
  `listEvents` and `streamEvents` (`events:read`), and `sendEvent`
  (`events:write`, plus `lesson_plans:write` when it asks for generation).
  Four need none (`security: []`): `getOpenApiDocument`,
  `getAsyncApiDocument`, `listApiVersions` and `getApiVersion`.
- **Events** (ADR 30.9.26aa). Every door carries one envelope, `{id, type,
  created_at, api_version, subject, data}`; `id` (`lgr_evt_…`) is the
  receiver's deduplication key, and delivery is at least once and
  unordered. The catalogue of types is the AsyncAPI document, closed and
  additive. `listEvents` pages with an opaque `next_cursor` (always present)
  and `has_more`; a cursor older than the 30-day window is `410
  cursor_expired`. `streamEvents` frames carry `id: <cursor>`, the same token
  as `next_cursor`, and the stream ends with `done` after 15 minutes or with
  `error`. `sendEvent` requires an `Idempotency-Key` header of 1–255 visible
  ASCII characters; a replay under the same key returns the first answer as
  an equal JSON value, and bodies are never compared.
- **Versions.** Every `/v1` request takes an optional `Lingara-Version`
  header, `^\d{4}-(0[1-9]|1[0-2])-[a-z]+-[a-z]+$`. Without it, a token gets
  its client's pinned version and a tokenless request gets the current one.
  An unknown version is `400 api_version_unknown`; a discontinued one is
  `410 api_version_discontinued`. A response under a named version echoes
  `Lingara-Version`. A deprecated version's responses carry
  `Deprecation: @<unix seconds>`, `Sunset: <IMF-fixdate>` and
  `Link: </v1/versions/<id>>; rel="deprecation"; type="application/json"`
  (a relative reference).
- **Refusals.** A `/v1` refusal is `{code, error}`, with `error` localised.
  Status to code: 400 `invalid_request`, 401 `unauthorized`, 403 `forbidden`,
  404 `not_found`, 409 `conflict`, 422 `refused`, 429 `rate_limited`, 502
  `upstream_unavailable`, 503 `unavailable`, anything else `internal`. Named
  codes beside that table include 403 `insufficient_scope`, 403
  `metered_unavailable`, 402 `spend_cap_reached`, 402
  `metered_billing_inactive`, 400 `api_version_unknown` and 410
  `api_version_discontinued`.
- **`Retry-After`** is delta-seconds, rounded up, never `0`. Not every 429 or
  503 carries one: some rate-limit refusals do not, and neither does the
  maintenance response.
- **Maintenance.** During maintenance every route, `/oauth/token` included,
  answers `503` with a plain-text body (`Service is under maintenance. Please
  try again later.`), no JSON envelope and no `Retry-After`.
- **The token endpoint.** `POST /oauth/token`, `grant_type=client_credentials`,
  optional `scope` (blank means every scope the client is allowed; one unknown
  or disallowed scope refuses the whole request). Client authentication is
  exactly one of `client_secret_basic` or `client_secret_post`; both at once
  is `invalid_request`. Each Basic half is form-decoded (`+` is a space, then
  percent-decoding), per RFC 6749 §2.3.1. A `200` is `{access_token:
  "lgr_at_…", token_type: "Bearer", expires_in: 3600, scope: "<space-delimited>"}`
  with `Cache-Control: no-store` and `Pragma: no-cache`; there is no refresh
  token. An error is RFC 6749 §5.2 `{error, error_description}`, never
  localised: 400 `invalid_request`, 401 `invalid_client` (with
  `WWW-Authenticate: Basic realm="lingara"`), 400 `unauthorized_client`, 400
  `unsupported_grant_type`, 400 `invalid_scope`, 429 `rate_limited` (with
  `Retry-After`), 500 `server_error`. Its limits are hourly rolling windows,
  so its `Retry-After` can be up to 3600.
- **Credentials.** Client ids are `lgr_cid_` plus 22 characters, secrets
  `lgr_cs_` plus 43, access tokens `lgr_at_…`.
- **Streams.** Server-sent events: `event:` plus one `data:` line of JSON,
  frames separated by a blank line, keepalive the comment `: keepalive`. No
  `retry:` field is ever sent, and only `streamEvents` sends `id:`. `generateVocabulary` sends
  `started`, `item`…, `done`; `sendTutorMessage` sends `delta`…, an optional
  `notice`, `done`; `createLessonPlan` sends `started`, `phase`…, then
  `result`, with no `done`, and a plan served from the library is a lone
  `result`; `streamLessonPlan` sends `started`, `phase`, then `result` or
  `pending`. Any stream may end on `error` `{code, message, plan_id?}`
  instead. `done`'s payload is `{}`. The vocabulary and lesson-plan streams
  send a keepalive every 15 s; the tutor stream sends none.

## K1 — the token source

**Interface.** A token source has two operations:

- `token()` returns an access token, or a K3 error;
- `invalidate(token)` forgets `token` **only if** it is still the cached
  value (compare-and-clear), so a stale 401 cannot throw away a newer token.

The client calls only these two. `ClientCredentials` is the implementation
this contract specifies; a caller MAY supply their own.

**The exchange.**

- `POST <token_url>`, `Content-Type: application/x-www-form-urlencoded`,
  `Accept: application/json`, body `grant_type=client_credentials`, plus
  `scope=<space-delimited>` only when the caller configured scopes.
- The default authentication is `client_secret_basic`:
  `Authorization: Basic base64(form_urlencode(client_id) ":" form_urlencode(client_secret))`.
  `client_secret_post` MUST be available as an option. A library MUST NOT
  send both.
- `token_url` defaults to `https://api.getlingara.com/oauth/token` and is
  overridable.
- A `200` whose body lacks `access_token` or `expires_in`, or whose
  `token_type` is not `Bearer` (case-insensitive), is
  `TransportError{kind: malformed_response}`.
- A non-2xx with an RFC 6749 body is `OAuthError`. The plain-text 503 is
  `MaintenanceError`. Any other non-2xx (a proxy's HTML 502, an empty 500) is
  `OAuthError` with `error = "http_<status>"` and no `description`.

**Caching and refresh timing.**

- `obtained_at` is the instant the exchange request was **sent**, not when
  the answer arrived.
- A token is stale at `obtained_at + expires_in − skew`, where
  `skew = min(60 s, expires_in / 2)`. With a 3600 s token that is **60 s
  before expiry**; the `expires_in / 2` bound keeps a token of
  `expires_in ≤ 60` from being stale on arrival.
- A stale token is replaced on the next `token()` call. There is no
  background timer: a library never wakes a process to refresh.

**Single-flight.** While an exchange is in flight, every concurrent `token()`
waits on it: N callers produce **one** `POST /oauth/token`. If the exchange
fails, every waiter gets the same error and nothing is cached; the next
`token()` starts a new exchange. There is no negative caching.

**A bounded exchange.** A cancelled waiter never aborts the shared exchange
(see Cancellation), so the exchange carries its own deadline. Each HTTP
attempt of the exchange MUST time out after a **token request timeout**, a
client option defaulting to **30 s**. On expiry every waiter gets
`TransportError{kind: timeout}` and nothing is cached. A library that
accepts a caller-supplied HTTP client MAY leave this timeout to that client,
and says so.

**The one 401 retry.** When a `/v1` call that carried a source-issued token
answers `401`:

1. `invalidate(that token)`;
2. `token()` again (a fresh exchange, unless a concurrent caller already
   replaced it);
3. repeat the call **once**.

A second `401` is `ApiError{status: 401, code: "unauthorized"}`. The 401
retry is separate from K4's attempt budget. It applies to a stream too,
because a 401 arrives before any event. It never applies to `/oauth/token`
itself, where 401 is `invalid_client`.

**Operations that need no token** never call `token()` and never send
`Authorization`. A client built without credentials MUST still call them.

**Redaction.** The client secret and every access token:

- MUST NOT appear in any debug, string, inspect or log rendering of the
  client, the token source, a request, a response or an error; they render
  as `[REDACTED]`;
- MUST NOT appear in an error message or a wrapped cause, including a
  transport error whose underlying library echoes the request;
- MAY be exposed through one explicitly named accessor (`expose_secret`
  style) for callers who need the raw value.

The `client_id` is not secret and is rendered.

`invalidate` during an exchange is a no-op: nothing is cached yet, and the
flight's result is newer than any token the caller holds.

## K2 — the version pin and the deprecation hook

- A client MAY be configured with a version id. When it is, **every** `/v1`
  request carries `Lingara-Version: <id>`. When it is not, none does, and the
  server applies the client's server-side pin. A library MUST NOT invent a
  default version, and MUST NOT validate the id beyond non-empty: the
  server's `400 api_version_unknown` is the answer.
- `Lingara-Version` is never sent to `/oauth/token`.
- The response's `Lingara-Version` echo is exposed on every non-stream result
  and on every stream as `served_version` (absent when the server sent none).
- When a response carries `Deprecation`, the library calls the caller's
  **deprecation hook** once for that response, before returning (for a
  stream: after the headers, before the first event is yielded), with:
  - `version`: the `Lingara-Version` echo;
  - `deprecated_at`: `Deprecation` parsed from `@<unix seconds>`;
  - `sunset_at`: `Sunset` parsed from IMF-fixdate, or absent;
  - `link`: the raw `Link` value, plus its target resolved against the
    request URL (RFC 8288 §3.2).

  An unparseable header is passed raw with the parsed field absent, never an
  error.
- With no hook configured, the library logs **one** warning per version id
  per client instance through the language's standard logging facility, and
  nothing else. A hook replaces that warning.
- A hook that throws or panics does not fail the call; its failure is
  swallowed and logged at debug.
- A library records the version its models were generated from. When a
  response's `Lingara-Version` echo differs from it, the library logs one
  warning per served id per client instance. It never sends the
  generated-for id as a header unless the caller configured it. (ADR
  30.9.26a §4: the warning has its own per-id dedup, separate from the
  deprecation warning's, and needs no hook; a response with no echo is
  silent.)

## K3 — one error family

| Variant | When | Fields |
|---|---|---|
| `ApiError` | a `/v1` non-2xx that is not a plain-text 503, **or** a stream's `error` event | `status` (the HTTP status; `200` for an `error` event), `code`, `message` (the envelope's `error`, or the event's `message`), `retry_after` (seconds, or absent), `plan_id` (only from an `error` event that carried one), `served_version` |
| `OAuthError` | a token-endpoint non-2xx that is not a plain-text 503 (`http_<status>` when the body is not RFC 6749) | `status`, `error`, `description` (`error_description`), `retry_after` |
| `MaintenanceError` | any `503` whose `Content-Type` is not JSON | `body` (the text, truncated to 1 KiB), `retry_after` (read if present) |
| `TransportError` | no usable HTTP answer: connect or TLS failure, reset, EOF before a stream's terminal event, a body that does not decode, an idle stream timeout | `kind` ∈ `connect`, `tls`, `reset`, `timeout`, `stream_ended_early`, `malformed_response`, `malformed_event`; `cause` (redacted) |

- **Precedence.** A `503` whose `Content-Type` is not JSON is always
  `MaintenanceError`, from either endpoint, whatever else it looks like.
- Otherwise, a `/v1` non-2xx whose body is not the envelope (a proxy's HTML
  502, an empty 500) is `ApiError` with `code = "http_<status>"`, such as
  `http_502`. The prefix cannot collide with a server code.
- The variants share a common base type or trait, so one `catch` or one
  `match` handles all four. Cancellation is **not** a variant: it surfaces as
  the language's own cancellation.
- Every variant is redacted per K1.

## K4 — retries

| Parameter | Default | Why |
|---|---|---|
| `max_attempts` | **3** (the first try and two retries) | The server's limiters are rolling windows; a third refusal in a row means the caller is over a real limit |
| `retry_after_cap` | **60 s** | A `Retry-After` can be an hour or more. A call that silently sleeps an hour is worse than an error naming the wait |
| retries enabled | yes; `max_attempts = 1` turns them off | |

**Retried:** a `429` or `503` from `/v1` or `/oauth/token` that carries a
`Retry-After` of at most `retry_after_cap`, while attempts remain. The
library waits exactly that long, with no jitter, then repeats the request
unchanged. `Retry-After` is read as delta-seconds or as an HTTP-date (then
`max(0, date − now)`, rounded up, `now` from the clock seam).

An operation whose spec requires `Idempotency-Key` sends the same key on
every attempt (ADR 30.9.26aa D8). When the caller gives none, the library
generates a UUIDv4 from the platform CSPRNG once per call, before the first
attempt; a caller's key is sent unchanged and is not validated locally.

**Not retried, raised at once:**

- a `429` or `503` with **no** `Retry-After`, including the maintenance 503;
- a `Retry-After` above the cap; the error carries `retry_after`, so the
  caller can schedule it;
- any other status;
- any `TransportError` (a reset request may already have spent allowance,
  and the library cannot know);
- a cancelled call.

**Before the body, never after.** A retry decision is made on the status line
and headers alone. A request is retried only if no byte of its response body
has been handed to the caller. A stream that has yielded even one event is
**never** replayed: its failure is raised, and the caller decides (for a
lesson plan, `streamLessonPlan` with the `plan_id` from `started`).

**How the two retries compose.** Each HTTP request has its own budget of
`max_attempts`. The token exchange is one request, so a 429 at `/oauth/token`
is retried against the exchange's budget, not the `/v1` call's. The `/v1`
request is another, and the one 401 retry starts it again with a **fresh**
budget, because the repeated request is a new request under a new token. So
one call makes at most one 401 retry, and at most `max_attempts` tries of
each request inside it. Each `Retry-After` sleep and each token exchange
honours cancellation.

## K5 — streams

**Request.** `Accept: text/event-stream`, plus the headers every call
carries. A `200` whose `Content-Type` is not `text/event-stream` is
`TransportError{kind: malformed_response}`. A non-2xx before the stream opens
is handled like any other response: K1's 401 retry, K4's retries, K3's error.

**Parsing** (the subset of WHATWG HTML §9.2 the server uses):

- bytes are decoded as UTF-8 **across chunk boundaries**: a multi-byte
  character may be split between chunks;
- lines end at `\r\n`, `\n` or `\r`;
- a line starting with `:` is a comment and is dropped;
- `event` sets the name; each `data` line appends to the data, joined by
  `\n`; one optional space after the colon is stripped; `retry` and unknown
  fields are ignored;
- `id` sets the last-event-id buffer, unless its value contains U+0000, in
  which case the field is ignored. The buffer persists across frames until
  the next `id` field, as WHATWG defines it. It is recorded on every stream
  and used only by K5a;
- a blank line dispatches the frame. A frame with no `data` is dropped. A
  frame with no `event` is named `message`;
- the decoded sequence MUST be identical however the bytes were chunked.

**Decoding.** Each frame's `data` is JSON, decoded into the generated model
for that operation's event.

**Ending.** Which events end each stream is the spec's, not this contract's:
each `x-lingara-streams` entry of the generator view names them as `endsOn`,
and its failure event as `error`, copied from the Backend's
`x-lingara-stream` (ADR 29.9.26ai). Each library generates its terminal
table from the view and holds none by hand. The rule over them is:

1. the entry's `error` event **raises**
   `ApiError{status: 200, code, message, plan_id}` (`plan_id` when the
   payload has one);
2. an ending event whose payload is `Done` (the empty object `{}`) **ends**
   iteration unyielded;
3. any other ending event is **yielded**, and then iteration ends.

Every event that does not end the stream is yielded.

- An **unknown event name** is skipped, not an error: new events are
  additive, and an old library must keep working.
- A known event whose `data` does not decode is
  `TransportError{kind: malformed_event}`.
- After a terminal event the library closes the connection and ignores any
  later bytes.
- EOF before a terminal event is `TransportError{kind: stream_ended_early}`.
- **Idle timeout.** The stream fails with `TransportError{kind: timeout}`
  after **120 s** in which no byte arrives. Any byte, a keepalive comment
  included, resets the timer. The timer runs **only while a read is
  pending**: time the caller spends holding an event, and time before the
  first read, never counts, because a slow consumer is not a silent server.
  It MUST be a client option; a library that accepts a caller-supplied HTTP
  client MAY leave that client's timeout to the caller, and says so. There is
  no total-duration timeout on a stream. 120 s is eight missed 15 s
  keepalives; for the tutor stream, which sends none, it bounds the model's
  time to first token and the gaps between deltas. A server change to any
  keepalive interval re-opens this number.
- There is no automatic reconnection and no `Last-Event-ID` on a K5 stream.
  Rejoining a lesson plan is the caller's explicit `streamLessonPlan`. The
  one exception is the tail helper of a resumable entry, which K5a governs.

## K5a — the tail, a stream that resumes

Decided by ADR 30.9.26aa D7. The view marks an `x-lingara-streams` entry
`resumable: true` only when it is a **tail**: its `error` event is in
`endsOn`, and every other ending event's payload is `Done`, so an ending
carries nothing for the caller and only moves the cursor. On such an entry
`endsOn` ends the **connection**, not the subscription. Today the one tail
is `streamEvents`.

Each tail has two methods, as the feed has: the operation itself
(`streamEvents`), the raw one-connection stream under K5 that ends on `done`
or `error`; and the **tail helper** (`tailEvents`), built on it. Every rule
below except K5's parsing rule binds the tail helper only.

- **`cursor`.** The tail exposes `cursor`: the `id:` of the last frame that
  carried one, an `event` frame **or a `done`**, so a tail that has seen no
  event still advances to the horizon. It seeds from the caller's `cursor`
  option, sent as `Last-Event-ID` on the first request and never as the
  `cursor` query fallback. Without a `cursor`, the first request carries
  `start` in the query when the caller gave one. Every reopen repeats the
  first request's URL (`types`, and `start` if any) and adds
  `Last-Event-ID: <cursor>`; the server ignores `start` and `cursor` under the
  header, so a tail opened with `start: oldest` never replays the backlog on
  a reconnect. The SSE `id:` and the feed's `next_cursor` are one token, so
  `tailEvents({cursor: feed.cursor})` hands a caller from catch-up to live
  with no gap, and the other direction works too.
- **Reconnect after any ending.** The tail never ends on its own:
  - a `done` is not yielded (K5 rule 2). Its `id:` becomes `cursor` and the
    tail reopens **at once**, with no delay; the reopen is not a failure;
  - an `error` event is not raised (unlike K5 rule 1). It is a failed
    reopen. Only when the bound below is spent is it raised, as
    `ApiError{status: 200, code}`;
  - EOF with no ending event, and every `TransportError` (connect, reset,
    `timeout`, and the idle timeout, which on a tail means "reconnect"), are
    failed reopens;
  - **the first open is a reopen**: a tail whose first request fails in any
    of these ways backs off rather than raising at once.
- **Backoff.** After a failure the delay is **1 s**, doubling on each
  consecutive failure up to **30 s**, through the sleeper seam. The failure
  count resets on the first `event` or `done` frame a connection delivers,
  **not** on its `200`: a `200` followed only by `error` is still a failure.
  After **8** consecutive failures the tail raises the last one, an
  `ApiError` for an `error` event and a `TransportError` otherwise. The bound
  is a client option; its default is 91 s of sleeps (1, 2, 4, 8, 16, 30, 30).
- **Reopen answers.** A tail open, the first included, **bypasses K4's
  attempt loop**: the tail's count is its only retry budget. A `401` gets
  K1's one refresh and is not a failure. A `429` or `503` is one failed
  reopen; its `Retry-After`, when within `retry_after_cap`, replaces that
  step's delay, and the doubling continues from the step count as if the
  backoff delay had been slept. A `Retry-After` above the cap is raised at
  once with `retry_after`. A `429` or `503` with none takes the backoff
  delay. `410 cursor_expired` and every other non-2xx are raised at once as
  K3 errors.
- **Events.** Each `event` frame's `data` is the event envelope, parsed into
  the library's `Event` union: an unknown `type` is `UnknownEvent`; a known
  type whose `data` does not decode is `TransportError{kind:
  malformed_event}`, raised and not reconnected, because a reopen from the
  same `cursor` would meet the same frame.
- **Cancellation** ends the tail at any point, including during a reconnect
  sleep, with no further request.
- K4's "a stream that has yielded an event is never replayed" is unchanged
  for every other stream. A tail reopen is not a replay: it asks the server
  for what comes **after** `cursor`.

## The event helpers

Decided by ADR 30.9.26aa D3, D6 and D8.

- **The union.** `Event` has one arm per outbound `x-lingara-events` entry,
  each holding `id`, `type`, `createdAt`, `apiVersion`, `subject` and a typed
  `data`, plus **`UnknownEvent`**, which holds the same envelope fields and
  `data` as the language's JSON value. `parseEvent` reads `type` first: a
  known type decodes into its arm, and its `data` failing to decode is an
  error; an **unknown** type is `UnknownEvent`, never an error, so a library
  older than the catalogue still acknowledges new events. `InboundEvent` has
  one constructor per inbound entry, named after and taking its `data`
  component, and serialises as `{type, data}`.
- **The feed.** `listEvents` is the operation: one page. The helper
  `events({cursor?, start?, types?})` sends `start` only when `cursor` is
  absent, walks pages, yields each item parsed into `Event`, and exposes
  `cursor`: after a page's last item is yielded, that page's `next_cursor`.
  It ends on a page with `has_more: false`. It never sleeps and never polls.
  `410 cursor_expired` and every other refusal are raised as K3 errors; a
  known type whose `data` does not decode is `TransportError{kind:
  malformed_event}`.
- **`sendEvent(event, {idempotencyKey?})`** posts `{type, data}` with K4's
  `Idempotency-Key` rule and returns the `202` body.

## Cancellation

- Every call accepts the language's native cancellation (`AbortSignal`,
  dropping a future or stream, `context.Context`, `Future.cancel` or
  `Thread.interrupt`, coroutine cancellation, and so on).
- Cancelling closes the underlying connection promptly: the server sees the
  disconnect within **2 s** of the last chunk it flushed.
- After cancellation no further event is yielded, no retry is attempted, and
  the call surfaces the language's own cancellation signal, **not** a K3
  variant.
- A cancel during a token exchange abandons only that caller's wait. The
  single flight continues for the other waiters, and its token is cached if
  it succeeds.
- A cancel during a `Retry-After` sleep ends the sleep and the call.

## K6 — identification

- Every request, `/oauth/token` included, carries
  `User-Agent: lingara-<lang>/<version> (<runtime>)`.
- `<lang>` is exactly one of `typescript`, `rust`, `go`, `java`, `kotlin`,
  `ruby`, `php`.
- `<version>` is the library's released version, including any pre-release
  suffix, at most 64 bytes.
- `<runtime>` is the library's choice, such as `node/20.11.1` or
  `jvm/17.0.9`: visible ASCII only, with no CR, no LF and no `)`. A value
  built from a tool's output keeps only the version number.
- A caller MAY append their own product token, separated by one space:
  `lingara-go/0.3.0 (go1.22.1) kanji-quest/2.1`. The library's token always
  comes **first**.

The pattern, which the conformance server checks on every request it
replays:

    ^lingara-(typescript|rust|go|java|kotlin|ruby|php)/(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)? \([\x20-\x28\x2A-\x7E]+\)( .+)?$

## Test seams

Refresh timing and `Retry-After` sleeps cannot be tested in real time, so
every library exposes, in its public configuration:

- a **clock** (`now()`), and
- a **sleeper** (`sleep(duration, cancellation)`).

Both default to the real ones and are documented as testing seams. The
conformance harness injects a virtual clock that advances only when a case
says so, and a sleeper that records each requested duration and returns at
once.

## Appendix W — the webhook verifier

Decided by ADR 30.9.26aa D4. Lingara signs each webhook delivery with the
[Standard Webhooks](https://www.standardwebhooks.com/) scheme. Every library
implements the verifier natively, with no dependency on an official
Standard Webhooks package, and
[`vectors/webhook-signatures.json`](vectors/webhook-signatures.json) holds
all of them to one answer. Verification makes no request, so it is not a
behaviour the cases exercise: each library's unit suite reads the vectors.

1. **Construction.** `Webhook(secret)` or `Webhook([secret, …])`; during a
   rotation two secrets are live. Each secret MUST be `lgr_whsec_` followed by
   **standard, padded base64** (`^[A-Za-z0-9+/]+={0,2}$`, length a multiple
   of 4, matched before decoding, since decoders differ in leniency) that
   decodes to at least 24 bytes. Anything else, a bare or `whsec_`-prefixed
   secret included, is refused at construction with the language's
   misconfiguration error. The HMAC key is the decoded remainder. Secrets
   are redacted in every rendering. The constructor takes the clock seam as
   an optional last argument.
2. **Headers.** `webhook-id`, `webhook-timestamp` and `webhook-signature`
   are read case-insensitively. A missing one is `missing_header`. A
   timestamp that is not one or more ASCII digits is `malformed_header`.
3. **Tolerance.** A timestamp more than **300 s** before `now` is
   `timestamp_too_old`; more than 300 s after, `timestamp_too_new`. Exactly
   300 s passes. The tolerance is not configurable.
4. **Signed content.** The UTF-8 bytes of `webhook-id`, `.`,
   `webhook-timestamp`, `.`, then the body **exactly as received, as
   bytes**. The verifier takes the raw body, never a parsed object.
5. **Compare.** `webhook-signature` is split on single spaces. Each `v1,<base64>`
   element is decoded; an element with another version prefix, or one that
   does not decode, is skipped. For each secret the expected HMAC-SHA256 is
   compared with each `v1` signature in **constant time** over the full 32
   bytes. Any match passes. None is `no_matching_signature`, and so is a
   header with no usable `v1` element.
6. **Parse.** Only after a match, the body is parsed into `Event`. A body
   that is not JSON, not an envelope, or a known type whose `data` does not
   decode is `malformed_payload`, and so is an envelope whose `id` differs
   from `webhook-id`.
7. `verify` returns the `Event` and stores nothing: deduplication by `id` is
   the receiver's.

`verifySignature(body, headers)` takes exactly `verify`'s arguments, runs
steps 1–5 and nothing else, and returns nothing on success, for a signed
body that is not an event envelope (an app-kit request).

**The error.** `WebhookVerificationError` carries one `reason` from
`missing_header`, `malformed_header`, `timestamp_too_old`,
`timestamp_too_new`, `no_matching_signature` and `malformed_payload`, and a
message that never contains a secret, a signature or the body. It is not a
K3 variant and sits **outside** the library's root error type: a caller's
catch-all around API calls must not also swallow a forged webhook.
