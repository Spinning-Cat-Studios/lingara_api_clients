# Changelog

## v0.1.0-alpha.10 — 2026-10-03


Generated from Lingara API 2026-10-affable-towhee (development) at spec backend@5b8cc07c1bbc2d2400e2d39b3a222e35912cde70.

### Added

- Webhooks and events in all seven libraries (ADR 30.9.26aa). The models are generated from the frozen version `2026-09-equipped-boxfish`, the first that carries the AsyncAPI event catalogue.
- A webhook verifier, `Webhook`, with `verify` (signature, then the body parsed into an `Event`) and `verifySignature` (signature only). It follows Standard Webhooks with `lgr_whsec_` secrets, accepts two secrets during a rotation, and has a fixed 300 s tolerance. It is implemented natively and checked against one shared vector file in every language. Its error sits outside the library's API error family.
- A typed `Event` union with one arm per event type. A type newer than the library comes back as `UnknownEvent`, never an error. Also `InboundEvent` for the events your game sends.
- `listEvents`, one page of the event feed, and the `events()` helper, which walks the pages from a cursor or a `start` and exposes the cursor to resume from. It never polls.
- `streamEvents`, the raw event stream, and the `tailEvents()` helper, which reconnects with `Last-Event-ID` after every ending (at once after `done`, otherwise on a 1 s to 30 s backoff) and gives up after 8 consecutive failures, a client option.
- `sendEvent`, which sends an `Idempotency-Key` on every attempt: a generated UUIDv4 unless you give your own.
- `getAsyncApiDocument`, the events document, which needs no token.

### Changed

- Every SSE decoder now records the `id` field. Only the event tail uses it; the other streams behave as before.
- Rust gains three runtime dependencies: `hmac` and `sha2` (RustCrypto) for the verifier, and `getrandom` for the idempotency key.
- `VersionDetail` gains `asyncapi`, the version's AsyncAPI document and its hash.

## v0.1.0-alpha.6 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Fixed

- Fixed the release build for the Ruby library, which stopped v0.1.0-alpha.5 from being published. The libraries, and the API spec they are generated from, are unchanged since v0.1.0-alpha.1.

## v0.1.0-alpha.5 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Changed

- Updated release tooling. The libraries, and the API spec they are generated from, are unchanged since v0.1.0-alpha.1.

## v0.1.0-alpha.4 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Changed

- Re-released with updated release tooling, so this release can be published with current Rust toolchains. The libraries, and the API spec they are generated from, are unchanged since v0.1.0-alpha.1.

## v0.1.0-alpha.3 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Changed

- Re-released with the latest release tooling, which can now publish a release into a repository that has no history yet. The libraries, and the API spec they are generated from, are unchanged since v0.1.0-alpha.1.

## v0.1.0-alpha.2 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Changed

- Re-released with the latest release tooling, whose publish step now succeeds only once the release tag has reached staging. The libraries, and the API spec they are generated from, are unchanged since v0.1.0-alpha.1.

## v0.1.0-alpha.1 — 2026-09-30


Generated from Lingara API 2026-09-equipped-boxfish (development) at spec backend@5b9b8331fe3e76fefc338c45aa65a7efa54c6b7e.

### Added

- The TypeScript library, `@lingara/api` on npm, published under the `next` tag as a pre-release.
- The Rust library, the `lingara` crate on crates.io, as a pre-release.
- The Go library, the module `github.com/Spinning-Cat-Studios/lingara_api_clients/go`, as a pre-release.
- The Java library, `com.getlingara:lingara-java` on Maven Central, as a pre-release.
- The Kotlin library, `com.getlingara:lingara-kotlin` on Maven Central, as a pre-release: `suspend` calls, streams as a single-collect `Flow`, and no Jackson.
- The Ruby library, the `lingara` gem on RubyGems, as a pre-release: no runtime dependency, and streams as a block or a single-use `Enumerable`.
- The PHP library, `spinningcatstudios/lingara` on Packagist, as a pre-release: PSR interfaces only, streams as `foreach` loops that `break` cancels, and an optional PSR-16 token cache for PHP-FPM.

### Changed

### Fixed

### Removed
