//! The changelog names its spec and every new language (ADR 29.9.26v D2, D7).

use std::path::Path;
use std::process::Command;

use serde_json::Value as Json;

use crate::Fail;
use crate::manifest::{LANGUAGES, Release, read};
use crate::publish::require_publish;

pub const UNRELEASED: &str = "changelog/unreleased.md";

/// The subject `scs-snapshot publish` gives the commit that promotes the
/// changelog; private carries no release tags, so this marks a release.
const PROMOTE_SUBJECT: &str = "changelog: promote unreleased -> v";

/// `Generated from Lingara API <info.version> (<state>) at spec <SOURCE>.`
pub fn spec_line(root: &Path) -> Result<String, Fail> {
    let spec: Json = serde_json::from_str(&read(root, "spec/openapi.json")?)
        .map_err(|e| Fail::input(format!("spec/openapi.json: {e}")))?;
    let info_version = spec
        .pointer("/info/version")
        .and_then(Json::as_str)
        .ok_or_else(|| Fail::input("spec/openapi.json: no info.version"))?;
    let registry: toml::Value = read(root, "spec/versions.toml")?
        .parse()
        .map_err(|e| Fail::input(format!("spec/versions.toml: {e}")))?;
    let state = registry
        .get("version")
        .and_then(toml::Value::as_array)
        .and_then(|versions| versions.iter().find(|v| v.get("id").and_then(toml::Value::as_str) == Some(info_version)))
        .and_then(|v| v.get("state").and_then(toml::Value::as_str))
        .unwrap_or("development");
    let source = read(root, "spec/SOURCE")?;
    Ok(format!("Generated from Lingara API {info_version} ({state}) at spec {}.", source.trim()))
}

/// D2 `check-changelog`: the spec line verbatim, and an `### Added` bullet
/// naming each entry the previous release's `languages.toml` lacked.
pub fn check_changelog(release: &Release) -> Result<Vec<String>, Fail> {
    require_publish(&release.root)?;
    let unreleased = read(&release.root, UNRELEASED)?;
    let line = spec_line(&release.root)?;
    let mut findings = Vec::new();
    if !unreleased.contains(&line) {
        findings.push(format!("{UNRELEASED}: does not carry the spec line `{line}`"));
    }
    let previous = previous_ids(&release.root)?;
    let added = added_bullets(&unreleased);
    for id in release.ids().into_iter().filter(|id| !previous.iter().any(|p| p == id)) {
        if !added.iter().any(|bullet| names(bullet, id)) {
            findings.push(format!("{UNRELEASED}: {id} is new since the last release; name it in an ### Added bullet"));
        }
    }
    Ok(findings)
}

fn git(root: &Path, args: &[&str]) -> Result<std::process::Output, Fail> {
    Command::new("git").arg("-C").arg(root).args(args).output().map_err(|e| Fail::input(format!("git: {e}")))
}

/// The ids in `languages.toml` at the newest promote commit; none when there
/// has been no release, or the file did not exist then.
fn previous_ids(root: &Path) -> Result<Vec<String>, Fail> {
    let log = git(root, &["log", "--format=%H %s"])?;
    if !log.status.success() {
        return Err(Fail::input(format!("git log: {}", String::from_utf8_lossy(&log.stderr).trim())));
    }
    let log = String::from_utf8_lossy(&log.stdout).into_owned();
    let Some(sha) = log.lines().find_map(|l| l.split_once(' ').filter(|(_, s)| s.starts_with(PROMOTE_SUBJECT))) else {
        return Ok(Vec::new());
    };
    let show = git(root, &["show", &format!("{}:{LANGUAGES}", sha.0)])?;
    if !show.status.success() {
        return Ok(Vec::new());
    }
    let text = String::from_utf8_lossy(&show.stdout);
    let file: toml::Value = text.parse().map_err(|e| Fail::input(format!("{LANGUAGES} at {}: {e}", sha.0)))?;
    let entries = file.get("language").and_then(toml::Value::as_array).cloned().unwrap_or_default();
    Ok(entries.iter().filter_map(|e| e.get("id").and_then(toml::Value::as_str).map(str::to_string)).collect())
}

/// The bullet lines under `### Added`, up to the next heading.
fn added_bullets(text: &str) -> Vec<&str> {
    let mut inside = false;
    let mut bullets = Vec::new();
    for line in text.lines().map(str::trim) {
        if line.starts_with('#') {
            inside = line == "### Added";
        } else if inside && (line.starts_with("- ") || line.starts_with("* ")) {
            bullets.push(line);
        }
    }
    bullets
}

/// `word` as a case-insensitive whole word: "Go library" names `go`,
/// "algorithm" does not.
pub fn names(text: &str, word: &str) -> bool {
    let (text, word) = (text.to_lowercase(), word.to_lowercase());
    text.match_indices(&word).any(|(i, _)| {
        let before = text[..i].chars().next_back();
        let after = text[i + word.len()..].chars().next();
        !before.is_some_and(char::is_alphanumeric) && !after.is_some_and(char::is_alphanumeric)
    })
}
