# Changelog

## v0.2.0-alpha.7 — 2026-10-31


Generated from Lingara API 2026-10-dauntless-hoopoe (development) at spec backend@39db1f0e8846700eeb2ded1e42f43238963eb4a1.

### Added

- Every library's mint request takes the optional `embed_version` (the embed version the player's build runs, as the player kit sends it in `Lingara-Embed-Version`), and every library's `MintedToken` carries `update_available` (`updateAvailable` in TypeScript, Java, Kotlin and PHP; `UpdateAvailable` in Go): Lingara's notice `{ latest, deprecated, message }` when that version is behind, deprecated or withdrawn, copied verbatim with its nulls, or none. A notice is advisory, so one that is malformed is dropped and the token is still returned. TypeScript exports the `UpdateAvailable` type. (ADR 31.10.26g)

### Changed

- The libraries are generated from the frozen `2026-10-expensive-sauger` contract, which brings `EmbedTokenRequest.embed_version`, the `UpdateAvailable` model and `EmbedToken.update_available` in every language.
- **Rust source break:** the generated `EmbedTokenRequest` has no `Default` or builder, so a caller that builds it as a struct literal adds `embed_version: None`. The crate is pre-1.0; the fix is one line. No other language's generated constructor changes shape: Kotlin's `embedVersion` lands last, after `origin`, so positional callers are unaffected.

### Fixed

- PHP's `TransportKindsTest` gives each token-stall case its own scripted server. Behind the previous case's stall, the mid-body case's request waited in the single-threaded server's backlog and failed before its headers, right at the 0.5 s bound, so a slow runner on the Symfony stack could map it to `connect` instead of `timeout`. Tests only; no library changes.

## v0.2.0-alpha.6 — 2026-10-29


Generated from Lingara API 2026-10-expensive-sauger (development) at spec backend@3258bda9aac81d3c416af8d8366cc3c50f572c3c.

### Fixed

- The Go conformance harness's `steps` and the test `TestDialoguePutPatchExportAndPaging` are split under revive's cyclomatic-complexity limit of 10, so `make lint-go` passes on the stable leg again. Tooling only; no library changes.

## v0.2.0-alpha.5 — 2026-10-29


Generated from Lingara API 2026-10-expensive-sauger (development) at spec backend@3258bda9aac81d3c416af8d8366cc3c50f572c3c.

### Added

- Canvases in all seven libraries (ADR 16.10.26ak), on a group the client hands out as `client.canvases` (`client.canvases()` in Rust, Java and PHP, `client.Canvases()` in Go): `listCanvases`, `getCanvas` and `dryRunCanvasPlan` (`canvas:read`), and `createCanvas`, `renameCanvas`, `saveCanvasDraft` and `activateCanvas` (`canvas:write`), each named after its `operationId` in the language's casing. There is no delete: a canvas is deleted through the platform's lifecycle routes.
- `pageCanvases(filters)` beside `listCanvases`: every canvas the filters match, following `next_cursor` until `has_more` is false and resending the filters on every page, in each language's iterator idiom. A page with `has_more` and no `next_cursor` is a `TransportError` of kind `malformed_response`.
- The libraries' first `PATCH` (`renameCanvas`) and a second `PUT` (`saveCanvasDraft`), each with a JSON body; the conformance case format accepts `PATCH`, and gains a `page_canvases` step.
- The canvas refusal codes, carried verbatim on `ApiError.code` and never enumerated: `canvas_invalid`, `canvas_schema_unsupported`, `canvas_too_large`, `canvas_objects_invalid`, `canvas_plan_invalid`, `canvas_plan_too_large`, `canvas_filter_invalid`, `canvas_cursor_invalid` (400), and `canvas_limit_reached` and `canvas_conflict` (409). A `409 canvas_conflict` is never retried: re-read with `getCanvas`, compare, and decide, as each README shows. A dry-run's refused verdict is data in a `200`, not an error.
- Stored dialogues in all seven libraries (ADR 29.10.26c), on a group the client hands out as `client.dialogues` (`client.dialogues()` in Rust, Java and PHP, `client.Dialogues()` in Go): `listDialogues`, `getDialogue` and `exportDialogue` (`dialogues:read`), and `createDialogue`, `renameDialogue` (`PATCH`), `saveDialogueDraft` (`PUT`) and `activateDialogue` (`dialogues:write`). There is no delete and no dry-run: a dialogue is deleted through the platform's lifecycle routes.
- `pageDialogues(filters)` beside `listDialogues`: every dialogue matching `name`, following `next_cursor` until `has_more` is false and resending the filters and `limit` on every page. A page with `has_more` and no `next_cursor` is a `TransportError` of kind `malformed_response`.
- `exportDialogue`'s result, a two-armed `ExportedDialogue`: `Body` (`revision`, `etag` and the IR as text, never parsed, so the bytes you hold are the bytes the `ETag` names) on a `200`, or `NotModified` (`revision`, `etag`) on a `304`, which is a result, never an error. Pass the `etag` you hold as `ifNoneMatch`; `revision` (`active` or `draft`) and `layout` (`keep` or `omit`) are sent only when given. A missing `ETag` or `Lingara-Dialogue-Revision`, or a revision that is not a non-negative integer, is `TransportError` `malformed_response`. Each library's pipeline gains one seam that lets this call read its `304` itself.
- The dialogue refusal codes, carried verbatim on `ApiError.code` and never enumerated: `dialogue_invalid`, `dialogue_schema_unsupported`, `dialogue_too_large`, `dialogue_graph_invalid`, `dialogue_cursor_invalid` (400), `dialogue_limit_reached` and `dialogue_conflict` (409), and `dialogue_not_active` and `dialogue_no_draft` (404). A `409 dialogue_conflict` is never retried: re-read with `getDialogue`, take `draft.draft_version` (or `0` with no draft), and decide. The `path` and `rule` that `dialogue_invalid` and `dialogue_graph_invalid` carry are not surfaced on `ApiError` in this release.
- The conformance case format gains a `page_dialogues` step, and fourteen dialogue cases (thirteen `op`, one K4).

### Breaking

- `sendDialogueTurn`'s request, regenerated from the spec at the mint: its `npc` is now a `DialogueNpc`, not an `Npc`, in TypeScript, Rust, Go, Java, Kotlin and PHP (Ruby builds it from a hash and is unaffected), and it gains `pedagogy`, `checkpoint`, `edges` and `facts`. `pedagogy` is required by TypeScript's type, and Rust's struct literal must name all four; they are optional elsewhere.

### Changed

### Fixed

### Removed

## v0.2.0-alpha.4 — 2026-10-23


Generated from Lingara API 2026-10-simple-tarpon (development) at spec backend@130ed5271f3b75399ca22466c8414e23073c2d04.

### Changed

- `lingara-java` and `lingara-kotlin` are published to Lingara's own Maven repository, `https://maven.getlingara.com`, from this version on, not to Maven Central. Add that repository to your build beside the dependency: a `<repository>` in Maven, or an `exclusiveContent` block for the `com.getlingara` group in Gradle, as each library's README shows. Every earlier version is on both, so pinning an older version keeps working either way.

## v0.2.0-alpha.3 — 2026-10-22


Generated from Lingara API 2026-10-simple-tarpon (development) at spec backend@130ed5271f3b75399ca22466c8414e23073c2d04.

### Added

- `linkEmbedPlayer` in all seven libraries (`link_embed_player` in Rust and Ruby, `LinkEmbedPlayer` in Go): `PUT /v1/embed/players/{player_ref}/link` links a player to the Lingara account that granted your app access, and returns the generated `EmbedPlayerLink` (ADR 22.10.26t). Call it on a client whose token source returns the player's delegated token from that account's consent; a client-credentials client gets `403 embed_link_needs_grant`. The library does not obtain that token yet, and each library's `linkEmbedPlayer` snippet shows the token source in a few lines.
- `unlinkEmbedPlayer` in all seven libraries: `DELETE` on the same path ends the link (`embed:mint`), completing on an empty `204`.
- The `EmbedPlayerLink` and `LinkStatus` models, and the conformance case format's `PUT` verb.

### Changed

- Every library is generated for API version `2026-10-virile-dragon`, the API's current version; it was `2026-10-golden-remora`.

- `release-manifest` reads three more opt-in `languages.toml` shapes, so the CLI installs it rather than forking it (ADR 22.10.26r): `artefact.uploaded` may be a list of names (space-joined in `matrix` and `artefact`, each expected on its own by `check-publish`), an entry may set `binary` (emitted as its `matrix` row's last key), and a version file may be `kind = "line"` (a line prefix ending in the value's opening quote). A file that uses none of them produces the same output and findings as before.

### Fixed

- The Go lint lane pins staticcheck v0.8.2: Go 1.27.3 writes export data that v0.8.1 cannot read, so `make lint-go` failed on the stable leg. Tooling only; no library changes.

### Removed

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
