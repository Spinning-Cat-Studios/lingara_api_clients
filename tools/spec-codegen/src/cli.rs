//! `spec-codegen (--input <openapi.json> | --registry <versions.toml>) --source <SOURCE> --out-dir <dir> [--check]`
//! (ADR 29.9.26m D4).
//!
//! `--registry` (ADR 30.9.26a §3) generates from the snapshot of the registry's
//! `current` version — the newest `supported`/`lts` entry by `minted_at`, the
//! rule Backend's `api_versions::lifecycle::current` holds, restated here
//! because this repo does not link Backend's crates — read from
//! `<registry dir>/versions/<id>.openapi.json`. A registry with no `current`
//! is a refusal, never a fallback to the live bundle.
//!
//! Exit 0: written, or `--check` found both dialects current. Exit 1:
//! `--check` found a dialect that differs from what would be written. Exit 2:
//! a refusal, or input that could not be read.

use std::fs;
use std::path::{Path, PathBuf};

use crate::{build_view, render};

const USAGE: &str =
    "usage: spec-codegen (--input <openapi.json> | --registry <versions.toml>) --source <SOURCE file> --out-dir <dir> [--check]";

/// The two files a run writes into `--out-dir`.
pub const OUTPUTS: [&str; 2] = ["openapi.3.1.json", "openapi.3.0.json"];

#[derive(Debug, Default, PartialEq, Eq)]
pub struct Args {
    pub input: PathBuf,
    pub registry: PathBuf,
    pub source: PathBuf,
    pub out_dir: PathBuf,
    pub check: bool,
}

pub fn parse(args: &[String]) -> Result<Args, String> {
    let mut out = Args::default();
    let mut it = args.iter();
    while let Some(flag) = it.next() {
        if flag == "--check" {
            out.check = true;
            continue;
        }
        let value = it.next().ok_or_else(|| format!("{flag} needs a value"))?;
        match flag.as_str() {
            "--input" => out.input = value.into(),
            "--registry" => out.registry = value.into(),
            "--source" => out.source = value.into(),
            "--out-dir" => out.out_dir = value.into(),
            _ => return Err(format!("unknown argument {flag}")),
        }
    }
    if out.input.as_os_str().is_empty() == out.registry.as_os_str().is_empty() {
        return Err("exactly one of --input and --registry is required".into());
    }
    if [&out.source, &out.out_dir].iter().any(|p| p.as_os_str().is_empty()) {
        return Err("--source and --out-dir are both required".into());
    }
    Ok(out)
}

pub fn run(args: &[String]) -> i32 {
    match parse(args).and_then(|parsed| generate(&parsed).map(|files| (parsed, files))) {
        Err(e) => {
            eprintln!("spec-codegen: {e}\n{USAGE}");
            2
        }
        Ok((parsed, files)) if parsed.check => check(&parsed.out_dir, &files),
        Ok((parsed, files)) => write(&parsed.out_dir, &files),
    }
}

/// The rendered dialects, in `OUTPUTS` order.
fn generate(parsed: &Args) -> Result<[String; 2], String> {
    let input =
        if parsed.registry.as_os_str().is_empty() { parsed.input.clone() } else { current_snapshot(&parsed.registry)? };
    let text = fs::read_to_string(&input).map_err(|e| format!("{}: {e}", input.display()))?;
    let spec = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", input.display()))?;
    let source = fs::read_to_string(&parsed.source).map_err(|e| format!("{}: {e}", parsed.source.display()))?;
    let view = build_view(&spec, source.trim()).map_err(|r| format!("refused: {r}"))?;
    Ok([render(&view.v31), render(&view.v30)])
}

/// `<registry dir>/versions/<current id>.openapi.json`.
pub fn current_snapshot(registry: &Path) -> Result<PathBuf, String> {
    let text = fs::read_to_string(registry).map_err(|e| format!("{}: {e}", registry.display()))?;
    let table: toml::Table = toml::from_str(&text).map_err(|e| format!("{}: {e}", registry.display()))?;
    let id = current_id(&table)
        .ok_or_else(|| format!("refused: {} has no supported or lts version", registry.display()))?;
    let dir = registry.parent().unwrap_or(Path::new("."));
    Ok(dir.join("versions").join(format!("{id}.openapi.json")))
}

/// The newest `supported`/`lts` entry by `minted_at`. The registry's
/// timestamps are canonical `YYYY-MM-DDTHH:MM:SSZ`, so they order as text.
fn current_id(table: &toml::Table) -> Option<&str> {
    let field = |v: &toml::Value, key: &str| v.get(key).and_then(toml::Value::as_str).map(str::to_owned);
    table
        .get("version")?
        .as_array()?
        .iter()
        .filter(|v| matches!(field(v, "state").as_deref(), Some("supported" | "lts")))
        .max_by_key(|v| field(v, "minted_at"))
        .and_then(|v| v.get("id")?.as_str())
}

fn check(dir: &Path, files: &[String; 2]) -> i32 {
    let mut stale = 0;
    for (name, want) in OUTPUTS.iter().zip(files) {
        let path = dir.join(name);
        if fs::read_to_string(&path).ok().as_ref() != Some(want) {
            eprintln!("spec-codegen: {} differs from the view; run make spec-view", path.display());
            stale += 1;
        }
    }
    i32::from(stale > 0)
}

fn write(dir: &Path, files: &[String; 2]) -> i32 {
    let result = fs::create_dir_all(dir).and_then(|()| {
        OUTPUTS.iter().zip(files).try_for_each(|(name, text)| fs::write(dir.join(name), text))
    });
    match result {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("spec-codegen: {}: {e}", dir.display());
            2
        }
    }
}
