# @lingara/api

The official TypeScript library for the [Lingara API](https://getlingara.com),
for **Node ≥ 22, Deno 2 and Bun 1**. It has **no runtime dependencies**:
every runtime it targets already ships `fetch`, `ReadableStream`,
`TextDecoder` and `AbortSignal`, so installing it installs nothing else.

## Not for the browser

This library is for your server. The Lingara API authenticates with a client
secret, and a secret shipped to a browser is published: anyone who opens the
page can read it and spend your allowance. The API sends no CORS headers for
third-party origins for the same reason. The
[authentication guide](https://getlingara.com/guides/authentication) explains
why credentials stay on a server.

So the `Lingara` constructor **refuses a `clientSecret` wherever a DOM
exists** (`globalThis.document` is defined), and there is no override. If
that fires in your own tests, run them in a `node` test environment rather
than `jsdom`. A client built without credentials, for the three public
operations, is not refused.

## Install

```sh
npm install @lingara/api
```

Deno: `import { Lingara } from "npm:@lingara/api";`. Bun: `bun add @lingara/api`.

## Quick start

```ts
import { Lingara } from "@lingara/api";

const client = new Lingara({
  clientId: process.env.LINGARA_CLIENT_ID!,
  clientSecret: process.env.LINGARA_CLIENT_SECRET!,
  // version: "2026-09-knowing-tenpounder",  // optional pin
  // onDeprecation: (notice) => metrics.warn(notice),
});

// A stream is an async iterable; the request starts on the first read.
const ac = new AbortController();
for await (const ev of client.generateVocabulary({ level: 2, source_lang: "en", target_lang: "zh", count: 8 }, { signal: ac.signal })) {
  if (ev.event === "item") console.log(ev.data.word, ev.data.translation);
}

// A JSON call resolves with the body; `servedVersion` rides beside it.
const usage = await client.getUsage();
console.log(usage.servedVersion, usage.allowance);
```

Thirteen methods, each named after its `operationId`: `generateVocabulary`,
`createLessonPlan`, `streamLessonPlan`, `sendTutorMessage` and
`streamEvents` return an `EventStream`; `getLessonPlan`, `getUsage`,
`listEvents`, `sendEvent`, `getOpenApiDocument`, `getAsyncApiDocument`,
`listApiVersions` and `getApiVersion` return a promise. The last four need
no credentials. Every method takes `{ signal?: AbortSignal }` last. The
`events` and `tailEvents` helpers are under *Webhooks and events* below.

- `break` out of a `for await`, or `stream.close()`, closes the connection.
- A stream's `error` event is thrown as an `ApiError` with `status: 200`;
  `done` ends iteration and is not yielded; `result` and `pending` are
  yielded, then iteration ends.
- `await stream.servedVersion` gives the `Lingara-Version` echo, and never
  rejects.

## Errors

Everything the library throws is a `LingaraError`: `ApiError` (a refusal from
`/v1`, or a stream's `error` event), `OAuthError` (the token endpoint),
`MaintenanceError` (a plain-text 503) or `TransportError` (no usable answer:
`kind` is `connect`, `tls`, `reset`, `timeout`, `stream_ended_early`,
`malformed_response` or `malformed_event`). Cancelling throws your signal's
own `reason`, never a `LingaraError`. `instanceof` works even when the ESM
and CommonJS builds are both loaded in one process.

The client secret and access tokens never appear in any rendering of the
client, its token source or an error; they show as `[REDACTED]`.

## Webhooks and events

Every door (a webhook, the feed, the live tail) carries the same event:
`{id, type, createdAt, apiVersion, subject, data}`, with `data` typed per
`type`. Delivery is at least once and unordered, so **deduplicate by `id`**.

**Verify the raw body first.** Hand `Webhook` the body exactly as it
arrived, as a string or bytes, never a parsed object: parsing first changes
the bytes the signature covers. In Express that is
`express.raw({ type: "application/json" })` on the webhook route; with
`node:http`, collect the request's chunks.

```ts
import { UnknownEvent, Webhook, WebhookVerificationError } from "@lingara/api";

const webhook = new Webhook(process.env.LINGARA_WEBHOOK_SECRET!); // or [old, new] while rotating
app.post("/lingara", express.raw({ type: "application/json" }), async (req, res) => {
  try {
    const event = await webhook.verify(req.body, req.headers);
    res.sendStatus(204); // answer fast: within 10 s, or it is retried
    if (event instanceof UnknownEvent) return console.log("newer event type", event.type, event.id);
    if (event.type === "lesson_plan.ready") console.log(event.data.plan_id);
  } catch (e) {
    if (e instanceof WebhookVerificationError) return res.status(400).send(e.reason);
    throw e;
  }
});
```

- A secret is `lgr_whsec_…`; anything else is refused when the `Webhook` is
  built. Timestamps more than 300 s from now are refused. A
  `WebhookVerificationError` (`reason`: `missing_header`,
  `malformed_header`, `timestamp_too_old`, `timestamp_too_new`,
  `no_matching_signature`, `malformed_payload`) is deliberately **not** a
  `LingaraError`, so a catch around API calls never swallows a forged
  delivery. `verifySignature` runs the signature checks alone, for a signed
  body that is not an event.
- **`UnknownEvent`** is an event type newer than this library. It is never an
  error: acknowledge it with a `2xx` (or the sender keeps retrying it for a
  day) and log it, since it means a newer library has more to offer.
- `parseEvent(json)` turns one event's JSON into the same union.

**The feed.** `client.events({ cursor?, start?, types? })` walks every event
from `cursor` to where the feed is caught up, then ends; it never sleeps or
polls. Save `feed.cursor` afterwards and pass it back next time. Without a
cursor, `start` is `"latest"` (from now on, the default) or `"oldest"`
(everything still kept). A cursor older than 30 days throws `ApiError` with
`code: "cursor_expired"`: start again without one, or with `start: "oldest"`.
`listEvents` is the raw one-page operation.

**The tail.** `client.tailEvents({ cursor?, start?, types? })` yields live
events and reconnects by itself from its `cursor` after every ending, with a
1, 2, 4 … 30 s backoff. After `tailMaxFailures` (8, about 90 s) failed
reopens in a row it throws the last failure; catch it and start again from
`tail.cursor` if your game should wait longer. A feed's `cursor` and a tail's
are one token, so `tailEvents({ cursor: feed.cursor })` goes from catch-up to
live with no gap. `streamEvents` is the raw single connection.

**Sending events.** `client.sendEvent(InboundEvent.worldContextChanged({…}))`
returns the `202` answer. Each call carries an `Idempotency-Key`; without
`idempotencyKey` the library generates one per call and sends it on every
retry of that call. Supply your own when your game may resend after a crash,
since a generated key is gone once the call returns. A reused key returns the
**first** answer, whatever the new body, so never reuse one for a different
event. With `generate: true`, only `reaction.plan_status === "generating"`
promises a `lesson_plan.ready` or `.failed` event; a `partial` or `complete`
plan is readable now, and an event for it may still arrive.

**Versions.** `data` is rendered at your OAuth client's pinned version, and
this library's types describe the version it was generated for (the one its
version warning names). Pin your client to that version.

## Options

| Option | Default |
|---|---|
| `clientId`, `clientSecret` | none: a credential-free client |
| `auth` | `"basic"` (`client_secret_basic`); `"post"` for `client_secret_post` |
| `scopes` | none: every scope the client is allowed |
| `tokenSource` | a `ClientCredentials` built from the options above; supply your own `TokenSource` instead of `clientSecret` |
| `baseUrl` / `tokenUrl` | `https://api.getlingara.com` / `…/oauth/token` |
| `version` | none: your client's pinned version |
| `onDeprecation` | one `console.warn` per deprecated version id |
| `maxAttempts` | `3`; `1` turns retries off |
| `retryAfterCapSeconds` | `60`: a longer `Retry-After` is thrown, with `retryAfter` set |
| `streamIdleTimeoutMs` | `120000`, counted only while a read is waiting |
| `tailMaxFailures` | `8`: consecutive failed reopens before `tailEvents` throws the last |
| `tokenRequestTimeoutMs` | `30000` |
| `userAgentSuffix` | none: appended after the library's own token |
| `fetch` | `globalThis.fetch` |
| `clock`, `sleeper` | the real ones |

`clock` and `sleeper` are **testing seams**: they let a test control refresh
timing and `Retry-After` sleeps without waiting in real time. Leave them
alone in production.

**The `version` option and the generated-for version.** The library's types
were generated from one frozen API version, which the warning below names. Leave
`version` unset and your OAuth client's server-side pin decides what is
served; the library never sends a default. When a response is served from
another version, the library logs one `console.warn` per served id: the
response shapes may differ from the types. Pin the OAuth client (or set
`version`) to the generated-for version, or upgrade the library.

## The contract

This library keeps the contract every official Lingara library keeps, and
runs its conformance suite on Node 22 and 24, Deno 2 and Bun 1:
[`conformance/CONTRACT.md`](../conformance/CONTRACT.md).

## Licence

MIT.
