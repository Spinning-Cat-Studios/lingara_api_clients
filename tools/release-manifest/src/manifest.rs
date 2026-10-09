//! `languages.toml` (ADR 29.9.26v D1) and `check`, the tree checks against
//! it (D2).
//!
//! ADR 1.10.26ag D2 widens both for the embed SDK, which installs this tool
//! from a clients tag rather than forking it. Every widening is opt-in: a
//! file that sets none of `registries`, `extra_assets`, `dir`, `snippets` or
//! `prefix` reads, checks and fails exactly as before.

use std::fs;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::{Fail, install, version};

pub const LANGUAGES: &str = "languages.toml";
pub const RELEASE_WORKFLOW: &str = ".github/workflows/release.yml";

/// The closed set of registries and the `release.yml` job that publishes to
/// each (D1, D6). The first six are the libraries' own; 1.10.26ag W1 adds the
/// last three, which a file admits only by naming them in `registries`.
pub const REGISTRIES: [(&str, &str); 9] = [
    ("npm", "publish-npm"),
    ("crates.io", "publish-crates"),
    ("rubygems", "publish-rubygems"),
    ("maven-central", "publish-maven"),
    ("go", "publish-go"),
    ("packagist", "publish-packagist"),
    ("nuget", "publish-nuget"),
    ("godot-assetlib", "publish-godot-assetlib"),
    ("fab", "publish-fab"),
];

/// How many of `REGISTRIES` a file without `registries` admits: the original six.
const ORIGINAL: usize = 6;

/// The registries whose package is `<dir>/` itself, so `<dir>/LICENSE` is the
/// package's licence. W5: none of W1's three is one.
const DIRECTORY_PACKAGES: [&str; 5] = ["npm", "crates.io", "rubygems", "go", "packagist"];

/// W4: stores with no publish API. No install line, no probe; a person lists them.
pub const STORES: [&str; 2] = ["godot-assetlib", "fab"];

/// W5: registries no scs-snapshot `[[manifest]]` kind reads, so none is required.
pub const UNMANIFESTED: [&str; 3] = ["nuget", "godot-assetlib", "fab"];

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct LanguagesFile {
    /// W1: the subset of `REGISTRIES` this file admits; absent = the original six.
    #[serde(default)]
    registries: Option<Vec<String>>,
    /// W7: `{version}` templates `verify.assets` holds beside the `uploaded` names.
    #[serde(default)]
    extra_assets: Vec<String>,
    #[serde(default)]
    language: Vec<Language>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Language {
    pub id: String,
    pub registry: String,
    pub package: String,
    /// W2: the entry's directory, when two entries share one or its name has a hyphen; absent = `id`.
    #[serde(default)]
    pub dir: Option<String>,
    /// W3: the directory holding its one proved `install.*`; absent = `snippets/<id>`.
    #[serde(default)]
    pub snippets: Option<String>,
    #[serde(default)]
    pub manifest: Option<String>,
    #[serde(default)]
    pub version_files: Vec<VersionFile>,
    #[serde(default)]
    pub artefact: Option<Artefact>,
    pub since: String,
}

impl Language {
    /// W2: the directory the entry's package is built from.
    pub fn directory(&self) -> &str {
        self.dir.as_deref().unwrap_or(&self.id)
    }

    /// W3: the directory holding the entry's one `install.*`, relative to the root.
    pub fn snippets_dir(&self) -> String {
        self.snippets.clone().unwrap_or_else(|| format!("snippets/{}", self.id))
    }

    /// W4: a store has no publish API, so no install line and no probe.
    pub fn is_store(&self) -> bool {
        STORES.contains(&self.registry.as_str())
    }
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
    /// W6: written before `VERSION` and checked with it (`"="` for an exact Cargo pin).
    #[serde(default)]
    pub prefix: Option<String>,
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
    pub registries: Option<Vec<String>>,
    pub extra_assets: Vec<String>,
}

impl Release {
    pub fn load(root: &Path) -> Result<Self, Fail> {
        let text = read(root, LANGUAGES)?;
        let file: LanguagesFile = toml::from_str(&text).map_err(|e| Fail::input(format!("{LANGUAGES}: {e}")))?;
        let version = read(root, "VERSION")?.trim().to_string();
        Ok(Self {
            root: root.to_path_buf(),
            version,
            languages: file.language,
            text,
            registries: file.registries,
            extra_assets: file.extra_assets,
        })
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

    /// W1: the registries this file admits, in `REGISTRIES` order.
    pub fn admitted(&self) -> Vec<&'static str> {
        let known = REGISTRIES.iter().map(|(r, _)| *r);
        match &self.registries {
            None => known.take(ORIGINAL).collect(),
            Some(named) => known.filter(|r| named.iter().any(|n| n == r)).collect(),
        }
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
    findings.extend(registries_findings(release));
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

/// W1: every id `registries` names is a known registry.
fn registries_findings(release: &Release) -> Vec<String> {
    let known: Vec<&str> = REGISTRIES.iter().map(|(r, _)| *r).collect();
    let named = release.registries.iter().flatten();
    named
        .filter(|n| !known.contains(&n.as_str()))
        .map(|n| format!("{LANGUAGES}: registries names \"{n}\", which is not one of {}", known.join(", ")))
        .collect()
}

fn entry_findings(release: &Release, lang: &Language, workflow: &str) -> Vec<String> {
    let id = &lang.id;
    let dir = lang.directory();
    let mut findings = Vec::new();
    if !release.root.join(dir).is_dir() {
        findings.push(format!("{dir}/: the directory does not exist"));
    }
    let admitted = release.admitted();
    match REGISTRIES.iter().find(|(r, _)| *r == lang.registry && admitted.contains(r)) {
        None => findings.push(format!("{LANGUAGES}: {id}: registry \"{}\" is not one of {}", lang.registry, admitted.join(", "))),
        Some((_, job)) if !has_job(workflow, job) => {
            findings.push(format!("{RELEASE_WORKFLOW}: no `{job}` job publishes {id}'s registry {}", lang.registry))
        }
        Some(_) => {}
    }
    if let Some(a) = &lang.artefact {
        findings.extend(template_finding(id, "built", &a.built, "semver"));
        findings.extend(template_finding(id, "uploaded", &a.uploaded, "version"));
    }
    // W4: a store's listing is a person's job, so it proves no install line.
    let snippets = lang.snippets_dir();
    let installs = install::install_files(&release.root, &snippets);
    if !lang.is_store() && installs.len() != 1 {
        findings.push(format!("{snippets}/: {} install.* files, exactly one expected", installs.len()));
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
    let placeholders = template.split('{').skip(1).filter_map(|s| s.split_once('}').map(|(p, _)| p));
    let bad: Vec<&str> = placeholders.filter(|p| *p != allowed).collect();
    (!bad.is_empty()).then(|| {
        format!("{LANGUAGES}: {id}: artefact.{field} uses {{{}}}; only {{{allowed}}} is allowed there", bad.join("}, {"))
    })
}

/// A directory-packaged registry ships `<dir>/LICENSE`, byte-equal to the root
/// file and a regular file, except that crates.io may follow a symlink (D2).
fn licence_finding(root: &Path, lang: &Language, licence: Option<&[u8]>) -> Option<String> {
    if !DIRECTORY_PACKAGES.contains(&lang.registry.as_str()) {
        return None;
    }
    let dir = lang.directory();
    let rel = format!("{dir}/LICENSE");
    let Ok(meta) = fs::symlink_metadata(root.join(&rel)) else {
        return Some(format!("{rel}: missing; the package holds only {dir}/, so it needs the root LICENSE"));
    };
    if meta.file_type().is_symlink() && lang.registry != "crates.io" {
        return Some(format!("{rel}: a symlink; {} needs a regular file", lang.registry));
    }
    (fs::read(root.join(&rel)).ok().as_deref() != licence).then(|| format!("{rel}: differs from the root LICENSE"))
}
