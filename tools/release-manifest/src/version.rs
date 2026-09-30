//! One version (ADR 29.9.26v D1–D3): the hand-kept files that carry it,
//! `bump`, `check-tag` and the RubyGems spelling.

use std::fs;
use std::path::Path;

use serde_json::Value as Json;
use toml_edit::{DocumentMut, Item, Value};

use crate::Fail;
use crate::manifest::{self, Kind, LANGUAGES, Release, VersionFile};

/// `X.Y.Z` with an optional `-<pre-release>`: the major and the suffix.
pub fn parse(version: &str) -> Option<(u64, Option<&str>)> {
    let (core, pre) = match version.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (version, None),
    };
    let parts: Vec<&str> = core.split('.').collect();
    let numeric = |p: &&str| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit());
    if parts.len() != 3 || !parts.iter().all(numeric) {
        return None;
    }
    let ident = |i: &str| !i.is_empty() && i.chars().all(|c| c.is_ascii_alphanumeric() || c == '-');
    if pre.is_some_and(|p| !p.split('.').all(ident)) {
        return None;
    }
    Some((parts[0].parse().ok()?, pre))
}

/// `-alpha.N`, `-beta.N` or `-rc.N`: the suffixes a pre-1.0 release may carry.
fn is_release_suffix(pre: &str) -> bool {
    let Some((stage, n)) = pre.split_once('.') else { return false };
    ["alpha", "beta", "rc"].contains(&stage) && !n.is_empty() && n.chars().all(|c| c.is_ascii_digit())
}

/// D2 `check-tag`: `v` + `VERSION`, a pre-release suffix while the major is
/// 0, and no entry still waiting for its first version.
pub fn check_tag(release: &Release, tag: &str) -> Vec<String> {
    let mut findings = Vec::new();
    let semver = tag.strip_prefix('v').unwrap_or("");
    if semver != release.version {
        findings.push(format!("{tag}: the tag is not v + VERSION (v{})", release.version));
    }
    match parse(semver) {
        None => findings.push(format!("{tag}: not vX.Y.Z[-pre]")),
        Some((0, pre)) if !pre.is_some_and(is_release_suffix) => {
            findings.push(format!("{tag}: while the major is 0 a release needs -alpha.N, -beta.N or -rc.N"))
        }
        Some(_) => {}
    }
    for lang in release.languages.iter().filter(|l| l.since == "next") {
        findings.push(format!("{LANGUAGES}: {} still has since = \"next\": run make bump-version", lang.id));
    }
    findings
}

/// RubyGems' spelling of a SemVer pre-release: `0.1.0-alpha.1` → `0.1.0.pre.alpha.1`.
pub fn gem_version(semver: &str) -> String {
    semver.replacen('-', ".pre.", 1)
}

/// Every `version_files` key that does not read `VERSION`.
pub fn version_findings(release: &Release) -> Vec<String> {
    let mut findings = Vec::new();
    for file in release.languages.iter().flat_map(|l| &l.version_files) {
        match read_keys(&release.root, file) {
            Err(e) => findings.push(e),
            Ok(values) => {
                for (key, value) in file.keys.iter().zip(values) {
                    if value.as_deref() != Some(release.version.as_str()) {
                        let found = value.map_or("nothing".to_string(), |v| format!("\"{v}\""));
                        findings.push(format!("{}: {key} reads {found}, VERSION is \"{}\"", file.path, release.version));
                    }
                }
            }
        }
    }
    findings
}

/// D2 `bump`: `VERSION`, every `version_files` key and every `since = "next"`,
/// then `check` over the result.
pub fn bump(root: &Path, to: &str) -> Result<Vec<String>, Fail> {
    if parse(to).is_none() {
        return Err(Fail::input(format!("bump: \"{to}\" is not X.Y.Z[-pre]")));
    }
    let release = Release::load(root)?;
    for file in release.languages.iter().flat_map(|l| &l.version_files) {
        write_keys(root, file, to)?;
    }
    write(root, LANGUAGES, &bump_since(&release.text, to)?)?;
    write(root, "VERSION", &format!("{to}\n"))?;
    Ok(manifest::check(&Release::load(root)?))
}

fn bump_since(text: &str, to: &str) -> Result<String, Fail> {
    let mut doc: DocumentMut = text.parse().map_err(|e| Fail::input(format!("{LANGUAGES}: {e}")))?;
    if let Some(tables) = doc.get_mut("language").and_then(Item::as_array_of_tables_mut) {
        for table in tables.iter_mut() {
            if let Some(since) = table.get_mut("since").and_then(Item::as_value_mut)
                && since.as_str() == Some("next")
            {
                replace(since, to);
            }
        }
    }
    Ok(doc.to_string())
}

/// A string value replaced in place, keeping its surrounding whitespace and comment.
fn replace(value: &mut Value, to: &str) {
    let decor = value.decor().clone();
    *value = Value::from(to);
    *value.decor_mut() = decor;
}

fn write(root: &Path, rel: &str, text: &str) -> Result<(), Fail> {
    fs::write(root.join(rel), text).map_err(|e| Fail::input(format!("{rel}: {e}")))
}

/// Each key's string value, `None` where the key is absent or not a string.
fn read_keys(root: &Path, file: &VersionFile) -> Result<Vec<Option<String>>, String> {
    let text = fs::read_to_string(root.join(&file.path)).map_err(|e| format!("{}: {e}", file.path))?;
    match file.kind {
        Kind::Json => {
            let doc: Json = serde_json::from_str(&text).map_err(|e| format!("{}: {e}", file.path))?;
            Ok(file.keys.iter().map(|k| doc.pointer(k).and_then(Json::as_str).map(str::to_string)).collect())
        }
        Kind::Toml => {
            let doc: DocumentMut = text.parse().map_err(|e| format!("{}: {e}", file.path))?;
            Ok(file.keys.iter().map(|k| toml_item(doc.as_item(), k).and_then(Item::as_str).map(str::to_string)).collect())
        }
    }
}

fn toml_item<'a>(item: &'a Item, dotted: &str) -> Option<&'a Item> {
    dotted.split('.').try_fold(item, |item, part| item.get(part))
}

/// JSON is rewritten whole at npm's own format (two-space indent, trailing
/// newline, key order kept); TOML is edited in place.
fn write_keys(root: &Path, file: &VersionFile, to: &str) -> Result<(), Fail> {
    let text = manifest::read(root, &file.path)?;
    let missing = |key: &str| Fail::input(format!("{}: no string at {key}", file.path));
    let out = match file.kind {
        Kind::Json => {
            let mut doc: Json = serde_json::from_str(&text).map_err(|e| Fail::input(format!("{}: {e}", file.path)))?;
            for key in &file.keys {
                *doc.pointer_mut(key).filter(|v| v.is_string()).ok_or_else(|| missing(key))? = Json::from(to);
            }
            serde_json::to_string_pretty(&doc).map_err(|e| Fail::input(e.to_string()))? + "\n"
        }
        Kind::Toml => {
            let mut doc: DocumentMut = text.parse().map_err(|e| Fail::input(format!("{}: {e}", file.path)))?;
            for key in &file.keys {
                let item = key.split('.').try_fold(doc.as_item_mut(), |item, part| item.get_mut(part));
                replace(item.and_then(Item::as_value_mut).filter(|v| v.is_str()).ok_or_else(|| missing(key))?, to);
            }
            doc.to_string()
        }
    };
    write(root, &file.path, &out)
}
