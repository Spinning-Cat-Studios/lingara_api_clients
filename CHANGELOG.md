# Changelog

## v0.2.0-alpha.1 — 2026-10-09


Generated from Lingara API 2026-10-virile-dragon (development) at spec backend@de35d01dd19e4ae8c6d576715a555a14d4370659.

### Added

- `release-manifest` reads seven opt-in `languages.toml` keys, so the embed SDK can release through this tool rather than a fork: a per-file `registries` subset of nine known registries (`nuget`, `godot-assetlib` and `fab` join the six), an entry's `dir` and `snippets` directories, store registries with no install line or probe, NuGet's `.nuspec` probe, a `prefix` on `version_files`, and `extra_assets` in the `verify.assets` set. A file that sets none of them, this repository's included, is checked exactly as before. The `matrix` rows also carry `dir` and `manual`. Tooling only; no library changes.
- All seven libraries are generated from API version `2026-10-golden-remora` (ADR 1.10.26w). They gain three operations: `createEmbedToken` mints a player's 15-minute embed token from a metered client holding `embed:mint`; `deleteEmbedPlayer` deletes a player and revokes its tokens, answering an empty `204` (the libraries' first `DELETE`); and `sendDialogueTurn` streams an NPC's reply (`embed:play`), opened without retries because each attempt is billed.
- `MintedToken`, the mint's result: its `token` renders `[REDACTED]` in every rendering and is read through the one exposing accessor, beside `expiresAt`, `expiresIn`, `subject`, `scopes` and `accountLinked`. A mint answer missing any of the six fields is `TransportError{kind: malformed_response}`.
- The event catalogue gains the inbound `world.practice_completed` and the outbound `practice.completed`, plus the apps pause's `app.disabled` and `app.enabled`, which the same version froze. Each is typed in the feed, the tail and the webhook verifier.

### Changed

- TypeScript encodes every path parameter as one segment, with every byte outside RFC 3986's unreserved set written as upper-case `%XX`. `encodeURIComponent` left `! ' ( ) *` unencoded; no existing id contains them, so no existing call changes on the wire.

### Fixed

### Removed

## v0.1.0-alpha.13 — 2026-10-06


Generated from Lingara API 2026-10-golden-remora (development) at spec backend@5901561d1d52a31e57207323a5b44667d2abac87.

### Added

- A publish precheck, `make check-release-current-version`, refuses a release whose vendored registry's current version is not the live API's `current`, so a library is never published generated for a version new clients are not pinned to. Tooling only; no library changes.

### Changed

### Fixed

### Removed

## v0.1.0-alpha.12 — 2026-10-06


Generated from Lingara API 2026-10-golden-remora (development) at spec backend@5901561d1d52a31e57207323a5b44667d2abac87.

### Added

### Changed

### Fixed

- The conformance coverage check now counts the operations the libraries generate, those in the frozen version's view, instead of every operation in the vendored spec. The spec is the development version and can carry operations no library has yet, such as the `/v1/embed` routes in `2026-10-golden-remora`, and the check had demanded conformance cases for them. No library changes.

### Removed

## v0.1.0-alpha.11 — 2026-10-06


Generated from Lingara API 2026-10-golden-remora (development) at spec backend@5901561d1d52a31e57207323a5b44667d2abac87.

### Added

### Changed

- Every library is now generated for the frozen version `2026-10-affable-towhee` (was `2026-09-equipped-boxfish`). New OAuth clients have been pinned to it since 2026-10-02, so `v0.1.0-alpha.10` logged a version-mismatch warning on every response to one. No model changes shape.

### Fixed

- The README install table now marks Go, Java, Kotlin, Ruby and PHP as pre-release and gives each an install line. It still called them "in development", although all six libraries are on their registries at `v0.1.0-alpha.10`.

### Removed

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
