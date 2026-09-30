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

Nine methods, each named after its `operationId`: `generateVocabulary`,
`createLessonPlan`, `streamLessonPlan` and `sendTutorMessage` return an
`EventStream`; `getLessonPlan`, `getUsage`, `getOpenApiDocument`,
`listApiVersions` and `getApiVersion` return a promise. The last three need
no credentials. Every method takes `{ signal?: AbortSignal }` last.

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
