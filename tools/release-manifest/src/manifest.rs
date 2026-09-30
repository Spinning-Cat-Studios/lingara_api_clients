//! `languages.toml` (ADR 29.9.26v D1) and `check`, the tree checks against
//! it (D2).

use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::{Fail, install, version};

pub const LANGUAGES: &str = "languages.toml";
pub const RELEASE_WORKFLOW: &str = ".github/workflows/release.yml";

/// The closed set of registries and the `release.yml` job that publishes to
/// each (D1, D6).
pub const REGISTRIES: [(&str, &str); 6] = [
    ("npm", "publish-npm"),
    ("crates.io", "publish-crates"),
    ("rubygems", "publish-rubygems"),
    ("maven-central", "publish-maven"),
    ("go", "publish-go"),
    ("packagist", "publish-packagist"),
];

/// The registries whose package is `<id>/` itself, so `<id>/LICENSE` is the
/// package's licence.
const DIRECTORY_PACKAGES: [&str; 5] = ["npm", "crates.io", "rubygems", "go", "packagist"];

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LanguagesFile {
    #[serde(default)]
    language: Vec<Language>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Language {
    pub id: String,
    pub registry: String,
    pub package: String,
    #[serde(default)]
    pub manifest: Option<String>,
    #[serde(default)]
    pub version_files: Vec<VersionFile>,
    #[serde(default)]
    pub artefact: Option<Artefact>,
    pub since: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Json,
    Toml,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct VersionFile {
    pub path: String,
    pub kind: Kind,
    pub keys: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Artefact {
    pub built: String,
    pub uploaded: String,
}

/// `languages.toml` and `VERSION`, as read from one root.
#[derive(Debug)]
pub struct Release {
    pub root: PathBuf,
    pub version: String,
    pub languages: Vec<Language>,
    pub text: String,
}

impl Release {
    pub fn load(root: &Path) -> Result<Self, Fail> {
        let text = read(root, LANGUAGES)?;
        let file: LanguagesFile = toml::from_str(&text).map_err(|e| Fail::input(format!("{LANGUAGES}: {e}")))?;
        let version = read(root, "VERSION")?.trim().to_string();
        Ok(Self { root: root.to_path_buf(), version, languages: file.language, text })
    }

    pub fn language(&self, id: &str) -> Result<&Language, Fail> {
        self.languages
            .iter()
            .find(|l| l.id == id)
            .ok_or_else(|| Fail::input(format!("{LANGUAGES}: no entry with id \"{id}\"")))
    }

    pub fn ids(&self) -> Vec<&str> {
        self.languages.iter().map(|l| l.id.as_str()).collect()
    }
}

pub fn read(root: &Path, rel: &str) -> Result<String, Fail> {
    fs::read_to_string(root.join(rel)).map_err(|e| Fail::input(format!("{rel}: {e}")))
}

/// Every tree check (D2 `check`), each finding naming its file.
pub fn check(release: &Release) -> Vec<String> {
    let workflow = fs::read_to_string(release.root.join(RELEASE_WORKFLOW)).unwrap_or_default();
    let licence = fs::read(release.root.join("LICENSE")).ok();
    let mut findings = id_lines(release);
    for lang in &release.languages {
        findings.extend(entry_findings(release, lang, &workflow));
        findings.extend(licence_finding(&release.root, lang, licence.as_deref()));
    }
    findings.extend(version::version_findings(release));
    findings
}

/// Each `id` is lowercase letters, unique, and written as its own
/// `id = "<id>"` line at column 0, so `grep -E '^id = "[a-z]+"$'` reads the
/// same list a TOML parser does (D1, D8).
fn id_lines(release: &Release) -> Vec<String> {
    let written: Vec<&str> =
        release.text.lines().filter_map(|l| l.strip_prefix("id = \"")?.strip_suffix('"')).collect();
    let mut findings = Vec::new();
    for (i, id) in release.ids().into_iter().enumerate() {
        if id.is_empty() || !id.chars().all(|c| c.is_ascii_lowercase()) {
            findings.push(format!("{LANGUAGES}: id \"{id}\" is not lowercase letters only"));
        }
        if release.ids()[..i].contains(&id) {
            findings.push(format!("{LANGUAGES}: id \"{id}\" appears twice"));
        }
        if written.get(i) != Some(&id) {
            findings.push(format!("{LANGUAGES}: id \"{id}\" is not written as its own line `id = \"{id}\"` at column 0"));
        }
    }
    findings
}

fn entry_findings(release: &Release, lang: &Language, workflow: &str) -> Vec<String> {
    let id = &lang.id;
    let mut findings = Vec::new();
    if !release.root.join(id).is_dir() {
        findings.push(format!("{id}/: the directory does not exist"));
    }
    match REGISTRIES.iter().find(|(r, _)| *r == lang.registry) {
        None => findings.push(format!("{LANGUAGES}: {id}: registry \"{}\" is not one of npm, crates.io, rubygems, maven-central, go, packagist", lang.registry)),
        Some((_, job)) if !has_job(workflow, job) => {
            findings.push(format!("{RELEASE_WORKFLOW}: no `{job}` job publishes {id}'s registry {}", lang.registry))
        }
        Some(_) => {}
    }
    if let Some(a) = &lang.artefact {
        findings.extend(template_finding(id, "built", &a.built, "semver"));
        findings.extend(template_finding(id, "uploaded", &a.uploaded, "version"));
    }
    let installs = install::install_files(&release.root, id);
    if installs.len() != 1 {
        findings.push(format!("snippets/{id}/: {} install.* files, exactly one expected", installs.len()));
    }
    findings
}

/// A job key is a two-space-indented `<job>:` line under `jobs:`.
fn has_job(workflow: &str, job: &str) -> bool {
    let key = format!("  {job}:");
    workflow.lines().any(|l| l.trim_end() == key)
}

/// `built` may use only `{semver}`, `uploaded` only `{version}` (D1).
fn template_finding(id: &str, field: &str, template: &str, allowed: &str) -> Option<String> {
    let bad: Vec<&str> = placeholders(template).into_iter().filter(|p| *p != allowed).collect();
    (!bad.is_empty()).then(|| {
        format!("{LANGUAGES}: {id}: artefact.{field} uses {{{}}}; only {{{allowed}}} is allowed there", bad.join("}, {"))
    })
}

fn placeholders(template: &str) -> Vec<&str> {
    template.split('{').skip(1).filter_map(|s| s.split_once('}').map(|(p, _)| p)).collect()
}

/// A directory-packaged registry ships `<id>/LICENSE`, byte-equal to the root
/// file and a regular file, except that crates.io may follow a symlink (D2).
fn licence_finding(root: &Path, lang: &Language, licence: Option<&[u8]>) -> Option<String> {
    if !DIRECTORY_PACKAGES.contains(&lang.registry.as_str()) {
        return None;
    }
    let rel = format!("{}/LICENSE", lang.id);
    let Ok(meta) = fs::symlink_metadata(root.join(&rel)) else {
        return Some(format!("{rel}: missing; the package holds only {}/, so it needs the root LICENSE", lang.id));
    };
    if meta.file_type().is_symlink() && lang.registry != "crates.io" {
        return Some(format!("{rel}: a symlink; {} needs a regular file", lang.registry));
    }
    (fs::read(root.join(&rel)).ok().as_deref() != licence).then(|| format!("{rel}: differs from the root LICENSE"))
}
