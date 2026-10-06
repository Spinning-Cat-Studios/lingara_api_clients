//! `check-current [--registry <path>] <versions.json>` (ADR 6.10.26i D2, D3):
//! the vendored registry's current entry against the deployed API's
//! `current`, the pin a new OAuth client receives now.
//!
//! The answer to `GET /v1/versions` is a file the shell fetched; this module
//! makes no network call. It reads only the answer's `current`; every
//! `minted_at` it compares comes from the vendored registry.
//!
//! A folder of its own only because `src/` is at its file budget.

use std::path::Path;

use serde_json::Value as Json;

use crate::Fail;
use crate::manifest::read;

/// The registry `check-current` reads without `--registry`.
pub const REGISTRY: &str = "spec/versions.toml";

const ENDPOINT: &str = "GET /v1/versions";
const USAGE: &str = "usage: release-manifest check-current [--registry <path>] <versions.json>";

/// The newest `supported`/`lts` entry by `minted_at` — `spec-codegen`'s
/// `current_entry` rule, restated; `cli_tests.rs` there pins the two together.
pub fn current_id(table: &toml::Table) -> Option<String> {
    let field = |v: &toml::Value, key: &str| v.get(key).and_then(toml::Value::as_str).map(str::to_owned);
    table
        .get("version")?
        .as_array()?
        .iter()
        .filter(|v| matches!(field(v, "state").as_deref(), Some("supported" | "lts")))
        .max_by_key(|v| field(v, "minted_at"))
        .and_then(|v| field(v, "id"))
}

/// The arguments after `check-current`, paths relative to `root`.
pub fn run(root: &Path, args: &[&str]) -> Result<String, Fail> {
    let (registry, answer) = match args {
        ["--registry", registry, answer] => (*registry, *answer),
        [answer] if !answer.starts_with("--") => (REGISTRY, *answer),
        _ => return Err(Fail::input(USAGE)),
    };
    let table: toml::Table =
        read(root, registry)?.parse().map_err(|e| Fail::input(format!("{registry}: {e}")))?;
    let vendored =
        current_id(&table).ok_or_else(|| Fail::input(format!("{registry}: no supported or lts version")))?;
    let live = live_current(&read(root, answer)?)?;
    check_current(&table, registry, &vendored, &live)
}

/// The answer's `current`, which must be a string.
fn live_current(answer: &str) -> Result<String, Fail> {
    let json: Json =
        serde_json::from_str(answer).map_err(|e| Fail::input(format!("{ENDPOINT}: the answer is not JSON ({e})")))?;
    match json.get("current") {
        Some(Json::String(id)) => Ok(id.clone()),
        Some(Json::Null) | None => Err(Fail::input(format!("{ENDPOINT}: the answer has no `current`"))),
        Some(_) => Err(Fail::input(format!("{ENDPOINT}: the answer's `current` is not a string"))),
    }
}

/// D3: equal passes; any disagreement refuses, naming the fix.
fn check_current(table: &toml::Table, registry: &str, vendored: &str, live: &str) -> Result<String, Fail> {
    if live == vendored {
        return Ok(format!("✓ generated for {vendored}, the API's current version\n"));
    }
    let minted = |id: &str| {
        let versions = table.get("version").and_then(toml::Value::as_array)?;
        let entry = versions.iter().find(|v| v.get("id").and_then(toml::Value::as_str) == Some(id))?;
        entry.get("minted_at").and_then(toml::Value::as_str).map(str::to_owned)
    };
    // The registry is append-only: an id it lacks was minted after the vendoring.
    let live_is_newer = match minted(live) {
        None => true,
        Some(at) => Some(at) > minted(vendored),
    };
    let finding = if live_is_newer {
        format!(
            "the API's current version is {live}, but {registry}'s current entry is {vendored}: \
             run make sync-api-spec (the app kit: make sync-app-spec), then regenerate"
        )
    } else {
        format!(
            "the API's current version is {live}, older than {registry}'s current entry {vendored}: \
             the Backend that froze {vendored} is not deployed, or this answer is up to an hour old: deploy, or retry"
        )
    };
    Err(Fail::findings(&[finding]))
}

#[cfg(test)]
mod current_tests;
