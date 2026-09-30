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
`Client::builder().build()?` is enough for them.

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
were generated from one frozen API version, which the warning below names. Leave
`version` unset and your OAuth client's server-side pin decides what is
served; the crate never sends a default. When a response is served from
another version, the crate logs one `log::warn!` per served id: the
response shapes may differ from the models. Pin the OAuth client (or set
`version`) to the generated-for version, or upgrade the crate.

## The contract

This library keeps the contract every official Lingara library keeps, and
runs its conformance suite in CI:
[`conformance/CONTRACT.md`](https://github.com/Spinning-Cat-Studios/lingara_api_clients/blob/main/conformance/CONTRACT.md).

## Licence

MIT.
