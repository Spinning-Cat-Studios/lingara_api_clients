# Lingara API client libraries

Official client libraries for the [Lingara API](https://getlingara.com): generate
vocabulary lists, create lesson plans, hold tutor conversations and read your
usage, from your own code.

The libraries are pre-release: every version before 1.0.0 carries an
`-alpha.N`, `-beta.N` or `-rc.N` suffix, and every install line pins one.

## Install

| Language | Package | Status | Install |
|---|---|---|---|
| TypeScript | `@lingara/api` (npm) | pre-release | `npm install @lingara/api@next` |
| Rust | `lingara` (crates.io) | pre-release | `cargo add lingara@<version>` |
| Go | `github.com/Spinning-Cat-Studios/lingara_api_clients/go` | in development | |
| Java | `com.getlingara:lingara-java` (Maven Central) | in development | |
| Kotlin | `com.getlingara:lingara-kotlin` (Maven Central) | in development | |
| Ruby | `lingara` (RubyGems) | in development | |
| PHP | `spinningcatstudios/lingara` (Packagist) | in development | |

Each row gains its install line when its library is released. The released
libraries are the ones `languages.toml` lists; every release publishes them all
at one version, and each release's notes name the exact version to pin.

## What is here

- `spec/openapi.json` — the Lingara API's OpenAPI 3.2 document, with
  `versions.toml` and the frozen versions under `spec/versions/`. `spec/SOURCE`
  names the commit it was taken from.
- `spec/generator/` — the *generator view* of that document, in OpenAPI 3.1 and
  3.0. No mainstream generator reads 3.2, so the four event-stream operations are
  lifted into named, discriminated unions and listed under `x-lingara-streams`,
  and everything else is downconverted. The view is an input to code generators
  only; the API serves `spec/openapi.json`.
- `tools/spec-codegen/` — the Rust tool that writes the view. It refuses any
  construct it cannot express rather than approximating it.

## Building

```sh
make help               # every target
make spec-view          # regenerate spec/generator/
make check-publishable  # the view is current (what CI runs)
cargo test --workspace
```

## Licence

MIT — see [LICENSE](LICENSE).
