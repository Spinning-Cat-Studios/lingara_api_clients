# lingara

The official Rust library for the [Lingara API](https://getlingara.com): async
on **tokio**, over **reqwest** and **serde**. It gives you a stream you can
`.next().await`, an error enum you can `match`, and a client secret that cannot
end up in a log by accident.

This library is for your server. The Lingara API authenticates with a client
secret; the [authentication guide](https://getlingara.com/guides/authentication)
explains why credentials stay on a server.

## Install

```sh
cargo add lingara
cargo add tokio --features macros,rt-multi-thread
```

**MSRV: Rust 1.87.** Edition 2024 turns on cargo's MSRV-aware resolver, and
CI builds and tests the packaged crate on 1.87, the way you receive it.

### Features

| Feature | Default | TLS |
|---|---|---|
| `rustls` | yes | rustls with the webpki roots |
| `native-tls` | no | the platform's TLS (OpenSSL on Linux) |

Enable at least one: the API is HTTPS-only, and building with neither fails
with a message naming both. For `native-tls` alone:
`lingara = { version = "…", default-features = false, features = ["native-tls"] }`.

## Quick start

```rust
use std::num::NonZeroU8;
use lingara::{Client, models::{GenerateVocabularyEvent, VocabRequest}};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let client = Client::builder()
        .client_credentials(std::env::var("LINGARA_CLIENT_ID")?, std::env::var("LINGARA_CLIENT_SECRET")?)
        // .version("2026-09-knowing-tenpounder")        // optional pin
        // .on_deprecation(|d| log::warn!("{d:?}"))      // optional hook
        .build()?;

    let request = VocabRequest { level: 2, source_lang: "en".into(), target_lang: "zh".into(), count: NonZeroU8::new(8) };
    let mut stream = client.generate_vocabulary(&request).await?; // headers in: auth and retries are done
    while let Some(event) = stream.next().await {
        if let GenerateVocabularyEvent::Item(item) = event? {
            println!("{} {}", item.word, item.translation);
        }
    }

    let usage = client.get_usage().await?;
    println!("{:?} {:?}", usage.served_version(), usage.allowance);
    Ok(())
}
```

Nine methods, each the `operationId` in snake case. `generate_vocabulary`,
`create_lesson_plan`, `stream_lesson_plan` and `send_tutor_message` resolve to
an `EventStream` once the response headers are in, so a refusal comes from
that `await` and an in-stream failure from the stream's items.
`get_lesson_plan`, `get_usage`, `get_open_api_document`, `list_api_versions`
and `get_api_version` resolve to an `ApiResponse<T>`, which derefs to `T` and
carries `served_version()`. The last three need no credentials, so
`Client::builder().build()?` is enough for them. The events methods
(`list_events`, `events`, `stream_events`, `tail_events`, `send_event` and
`get_async_api_document`) are under [Webhooks and events](#webhooks-and-events),
and the embed methods (`create_embed_token`, `delete_embed_player` and
`send_dialogue_turn`) under [Embedding Lingara](#embedding-lingara).

- `EventStream` implements `futures_core::Stream`, and also has its own
  `next()`, so the quick start needs no `futures` import.
- An `error` event is an `Err(Error::Api(..))` item with `status: 200`, then
  the end. `done` ends the stream unyielded; `result` and `pending` are
  yielded, then the stream ends.
- **Cancellation is drop.** Dropping the future or the `EventStream` (or
  `stream.close()`) closes the connection. Wrap a call in
  `tokio::time::timeout` or `select!` to bound it; there is no cancellation
  token.

The models are generated from the API's OpenAPI document. Timestamps and ids
are the server's strings, not `chrono` or `uuid` types. `VocabRequest.count`
is an `Option<NonZeroU8>`, because the spec bounds it below by 1.

## Errors

Every call ends in `lingara::Error`, one enum with four variants:

- `Api`: a refusal from `/v1`, or a stream's `error` event.
- `OAuth`: the token endpoint refused.
- `Maintenance`: a plain-text 503.
- `Transport`: no usable answer. Its `kind` is `Connect`, `Tls`, `Reset`,
  `Timeout`, `StreamEndedEarly`, `MalformedResponse` or `MalformedEvent`.

`Error::retry_after()` reads any variant's `Retry-After`. A `Retry-After`
longer than `retry_after_cap` is returned rather than slept.

The client secret and every access token are `secrecy::SecretString`s: their
`Debug` is `[REDACTED]`, and they have no `Display`. `AccessToken::expose_secret`
is the one way to read a token.

## Options

| Builder method | Default |
|---|---|
| `client_credentials(id, secret)` | none: a credential-free client |
| `auth` | `TokenAuth::Basic` (`client_secret_basic`); `TokenAuth::Post` for `client_secret_post` |
| `scopes` | none: every scope the client is allowed |
| `token_source` | a `ClientCredentials` from the options above; supply your own `Arc<dyn TokenSource>` |
| `base_url` / `token_url` | `https://api.getlingara.com` / `…/oauth/token` |
| `version` | none: your client's pinned version |
| `on_deprecation` | one `log::warn!` per deprecated version id |
| `max_attempts` | `3`; `1` turns retries off |
| `retry_after_cap` | 60 s |
| `stream_idle_timeout` | 120 s, counted only while a read is waiting |
| `tail_max_failures` | 8: the consecutive failed opens `tail_events` rides out (91 s of sleeps) |
| `token_request_timeout` | 30 s |
| `user_agent_suffix` | none: appended after the library's own token |
| `http_client` | a reqwest client with a 30 s connect timeout and no total timeout |
| `clock`, `sleeper` | the real ones |

`clock` and `sleeper` are **testing seams**: they let a test control refresh
timing and `Retry-After` sleeps without waiting in real time. Leave them
alone in production.

**Your own `http_client`.** Use it for proxies and pools. Do not give it a
total `timeout`: reqwest applies that to the body too, so it would cut every
long stream. The stream idle timeout is enforced by the library, so it still
holds on your client.

**The deprecation hook** runs inside the call, once per response under a
deprecated version. A hook that panics is caught and logged at debug, and
the call continues. Under `panic = "abort"` nothing can catch a panic, so
keep the hook panic-free there.

**The `version` option and the generated-for version.** The crate's models
were generated from one frozen API version, `lingara::GENERATED_FOR_VERSION`,
which the warning below names. Leave
`version` unset and your OAuth client's server-side pin decides what is
served; the crate never sends a default. When a response is served from
another version, the crate logs one `log::warn!` per served id: the
response shapes may differ from the models. Pin the OAuth client (or set
`version`) to the generated-for version, or upgrade the crate.

## Webhooks and events

Every event, through every door, is one envelope: `id`, `created_at`,
`api_version`, `subject` and a typed `data`. `lingara::events::Event` is a
`#[non_exhaustive]` enum with one variant per type (`LessonPlanReady`,
`LessonPlanFailed`, `UsageThresholdReached`, `WebhookTest`, `AppInstalled`,
`AppUninstalled`, `AppDisabled`, `AppEnabled`, `PracticeCompleted`) plus
`Unknown`; `event.id()` and `event.event_type()` read any of them.

**Pin your client to the version this crate was generated for**,
`lingara::GENERATED_FOR_VERSION`. An event's `data` is rendered at your
client's pinned version, and the variants hold this crate's models.

### Receiving webhooks

```rust
use lingara::events::Webhook;

let webhook = Webhook::new(&std::env::var("LINGARA_WEBHOOK_SECRET")?)?; // Webhook::with_secrets([old, new]) during a rotation
// In an axum handler: async fn hook(headers: HeaderMap, body: Bytes) -> StatusCode
let event = webhook.verify(&body, &headers)?;
```

- **Verify the raw body first.** `verify` takes `&[u8]`, the bytes exactly as
  they arrived; a body that has been parsed and re-serialised no longer
  matches its signature. In axum, take the body as `Bytes` (not `Json`) and
  pass the `HeaderMap` as-is. In actix-web, take `web::Bytes` and collect
  the headers in one line:
  `let headers: HashMap<String, String> = req.headers().iter().filter_map(|(k, v)| Some((k.to_string(), v.to_str().ok()?.to_owned()))).collect();`
  Header names are matched case-insensitively either way.
- A failure is a `VerifyError` (`MissingHeader`, `MalformedHeader`,
  `TimestampTooOld`, `TimestampTooNew`, `NoMatchingSignature`,
  `MalformedPayload`; `reason()` is the contract's snake_case name). It is
  deliberately **not** a `lingara::Error`, so a `match` on API errors never
  swallows a forged webhook. Answer it with a `400`.
- `Webhook::new` returns `BuildError::InvalidWebhookSecret` for a secret that
  is not `lgr_whsec_` plus padded base64. Its `Debug` never shows the secret.
  The timestamp tolerance is 300 s and is not configurable.
- **Answer `2xx` fast, and deduplicate by `event.id()`.** Delivery is at
  least once and unordered, and a slow or failed answer is retried for about
  a day. The verifier stores nothing.
- **Acknowledge `Event::Unknown` too, and log it.** It is a type newer than
  this crate. Refusing it would only make Lingara retry it.
- `verify_signature` checks the signature and timestamp alone, for a signed
  body that is not an event.

### The feed

`client.events(EventsOptions { cursor: saved, ..EventsOptions::default() })`
walks `GET /v1/events` page by page, as a `Stream` of `Result<Event, Error>`
with its own `next()`. When it ends, save `feed.cursor()`. It never sleeps
or polls, so call it again later from that cursor. Without a cursor, `start`
decides where to begin: `EventStart::Latest` (now, the default) or
`EventStart::Oldest` (everything still kept). A cursor older than 30 days is
`Error::Api` with `code` `cursor_expired`: start again with no cursor, or
with `start: Some(EventStart::Oldest)` to take what is still retained.
`list_events` is the single-page operation underneath.

### The tail

`client.tail_events(options)` follows the live stream, `GET
/v1/events/stream`, and reconnects by itself: at once after the server's
quarter-hourly `done`, and after a dropped connection with a backoff of 1, 2,
4, 8, 16, 30 and 30 s. The eighth failure in a row is returned
(`tail_max_failures` changes the bound), so an outage is never hidden for
ever. Its `cursor()` is the same token as the feed's, so
`tail_events(EventsOptions { cursor: feed.cursor().map(Into::into), ..EventsOptions::default() })`
moves from catching up to live with no gap. Drop it to stop, during a
reconnect sleep included. `stream_events` is the raw one-connection stream.

### Sending events

```rust
use lingara::events::{InboundEvent, SendEventOptions};

let event = InboundEvent::WorldContextChanged(scene);
let accepted = client.send_event(&event, SendEventOptions { idempotency_key: Some("game-save-17/scene-4".into()) }).await?;
```

- Without an `idempotency_key` the library generates a UUIDv4 for the call
  and sends it on every retry. Supply your own when your game may resend
  after a crash, because a generated key does not outlive the call. A
  transport failure is not retried; resend with your own key.
- **A reused key returns the first answer**, even for a different event: the
  server never compares bodies.
- Only a `reaction` whose `plan_status` is `Some(PlanStatus::Generating)`
  promises a `lesson_plan.ready` or `lesson_plan.failed` event. A `Partial`
  or `Complete` plan was served from the library and can be read now; an
  event may still arrive for it, so tolerate one.

## Embedding Lingara

A game or website can vouch for its own players: your server mints each
player a short-lived embed token, and the player's device uses it.

```rust
let minted = client.create_embed_token(&EmbedTokenRequest { player_ref: "player-1001".parse()?, scopes: None, origin: None }).await?;
// store minted.subject; hand minted.token.expose_secret() (lgr_et_…) to the device
```

- **Mint on your server, never on the player's device**, from a **metered**
  client holding `embed:mint` (a client built with explicit `scopes` must
  list it). Otherwise the answer is a `403` `insufficient_scope` or
  `embed_needs_metered`, returned as `Error::Api`. **Store `subject`**
  beside your player: it is the player's stable `lgr_sub_`, and how every
  event names them.
- `lingara::embed::MintedToken`'s `token` is an `AccessToken`, so it renders
  as `[REDACTED]` like every token here, and so does the `MintedToken`'s
  `Debug`; `expose_secret()` reads it. The token lives 900 s and Lingara
  never refreshes it, so mint again when the player kit asks. `expires_in`
  (a `Duration`) is there for a device whose clock cannot be trusted;
  `expires_at` (the server's RFC 3339 string) for one whose clock can.
- `delete_embed_player(player_ref)` deletes a player and revokes their
  tokens. `player_ref` is sent as one percent-encoded path segment, so
  `guild/42` is one player. An unknown player is still a success, so it is
  idempotent, and it keeps working while embedding is switched off for your
  client. The answer has no body: the `ApiResponse<()>` carries only
  `served_version()`.
- `send_dialogue_turn` streams an NPC's reply, `Delta` by `Delta`. The
  window is yours: at most 12 `history` entries, `line` and each entry at
  most 500 characters, and no total cap. Send each NPC reply back cut to its
  first 500 characters. A turn is **never retried**: each attempt spends the
  player's NPC cells and your metered cells, so a `429` or `503` is returned
  at once as `Error::Api` with its `retry_after`, and you decide whether to
  send it again. No retry helps `403 embed_needs_metered`, or
  `422 safety_input_flagged`, which means say something else.
- `Event::PracticeCompleted` arrives through the webhook, the feed and the
  tail when your client holds `events:read` and `embed:play`; its `subject`
  names the player. `InboundEvent::WorldPracticeCompleted` sends one, with
  `events:write` and `embed:play`.
- **A player-side caller** supplies its embed token through a custom
  `TokenSource` (`ClientBuilder::token_source`, wrapping it in
  `AccessToken::new`); no client secret is involved there. The library never
  sends `X-Lingara-Embed-Origin`, so a token minted with an `origin` belongs
  to the browser widget: a game mints without one.

The [embed kits](https://github.com/Spinning-Cat-Studios/lingara_embeddable_sdk)
build on these calls: server kits for higher-level minting and webhook
helpers, and player kits for the engines.

## The contract

This library keeps the contract every official Lingara library keeps, and
runs its conformance suite in CI:
[`conformance/CONTRACT.md`](https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md).

## Licence

MIT.
