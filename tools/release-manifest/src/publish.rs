//! `check-publish` (ADR 29.9.26v D1, D2): `languages.toml` against
//! `publish/`, which exists in private and staging only.

use std::path::Path;

use crate::Fail;
use crate::manifest::{self, Language, Release, UNMANIFESTED, read};

pub const SNAPSHOT: &str = "publish/snapshot.toml";
pub const ALLOWLIST: &str = "publish/public.allowlist";

/// A publish check run where `publish/` does not exist is exit 2, never a
/// pass on nothing.
pub fn require_publish(root: &Path) -> Result<(), Fail> {
    if root.join(SNAPSHOT).is_file() {
        Ok(())
    } else {
        Err(Fail::input(format!("{SNAPSHOT}: absent; this check runs in private and staging only")))
    }
}

/// `check`, plus the allowlist, the manifest linkage and `verify.assets`.
pub fn check_publish(release: &Release) -> Result<Vec<String>, Fail> {
    require_publish(&release.root)?;
    let snapshot: toml::Value =
        read(&release.root, SNAPSHOT)?.parse().map_err(|e| Fail::input(format!("{SNAPSHOT}: {e}")))?;
    let allowlist = read(&release.root, ALLOWLIST)?;
    let manifests = manifests(&snapshot);
    let mut findings = manifest::check(release);
    for lang in &release.languages {
        findings.extend(allowlist_findings(&allowlist, lang));
        findings.extend(manifest_finding(lang, &manifests));
    }
    findings.extend(asset_findings(release, &snapshot));
    Ok(findings)
}

/// The entry's directory and its snippets directory each lie under a
/// slash-terminated entry (1.10.26ag W2, W3: `dir` and `snippets` move them).
/// A store has no snippets to ship (W4), so only its directory is held.
fn allowlist_findings(allowlist: &str, lang: &Language) -> Vec<String> {
    let entries: Vec<&str> =
        allowlist.lines().map(str::trim).filter(|l| l.ends_with('/') && !l.starts_with('#')).collect();
    let mut dirs = vec![format!("{}/", lang.directory())];
    if !lang.is_store() {
        dirs.push(format!("{}/", lang.snippets_dir()));
    }
    dirs.into_iter()
        .filter(|dir| !entries.iter().any(|e| dir.starts_with(e)))
        .map(|dir| format!("{ALLOWLIST}: {dir} is not covered, so it would not ship"))
        .collect()
}

/// `(kind, path)` of every `[[manifest]]`.
fn manifests(snapshot: &toml::Value) -> Vec<(String, String)> {
    let entries = snapshot.get("manifest").and_then(toml::Value::as_array).cloned().unwrap_or_default();
    let field = |e: &toml::Value, k: &str| e.get(k).and_then(toml::Value::as_str).unwrap_or_default().to_string();
    entries.iter().map(|e| (field(e, "kind"), field(e, "path"))).collect()
}

/// D1's matching rule: exactly one `[[manifest]]` answers for each entry, under
/// its directory. 1.10.26ag W5: a NuGet or store entry that names none needs
/// none, since scs-snapshot has no kind that reads it.
fn manifest_finding(lang: &Language, manifests: &[(String, String)]) -> Option<String> {
    if lang.manifest.is_none() && UNMANIFESTED.contains(&lang.registry.as_str()) {
        return None;
    }
    let id = &lang.id;
    let dir = lang.directory();
    let under = |path: &str| path.starts_with(&format!("{dir}/"));
    match &lang.manifest {
        Some(path) => {
            let Some((kind, _)) = manifests.iter().find(|(_, p)| p == path) else {
                return Some(format!("{SNAPSHOT}: {id} names manifest {path}, which no [[manifest]] has"));
            };
            let root_gradle = kind == "gradle" && !path.contains('/');
            (!under(path) && !root_gradle)
                .then(|| format!("{SNAPSHOT}: {id}'s manifest {path} is neither under {dir}/ nor a root gradle manifest"))
        }
        None => {
            let hits: Vec<&str> = manifests.iter().map(|(_, p)| p.as_str()).filter(|p| under(p)).collect();
            (hits.len() != 1).then(|| {
                format!("{SNAPSHOT}: {} [[manifest]] entries under {dir}/ ({}), exactly one expected", hits.len(), hits.join(", "))
            })
        }
    }
}

/// `verify.assets` is the entries' `uploaded` names, the file's
/// `extra_assets` (1.10.26ag W7) and `checksums.txt`.
fn asset_findings(release: &Release, snapshot: &toml::Value) -> Vec<String> {
    let mut expected: Vec<String> =
        release.languages.iter().filter_map(|l| l.artefact.as_ref().map(|a| a.uploaded.clone())).collect();
    expected.extend(release.extra_assets.iter().cloned());
    expected.push("checksums.txt".to_string());
    let actual: Vec<String> = snapshot
        .get("verify")
        .and_then(|v| v.get("assets"))
        .and_then(toml::Value::as_array)
        .map(|a| a.iter().filter_map(toml::Value::as_str).map(str::to_string).collect())
        .unwrap_or_default();
    let missing = expected.iter().filter(|e| !actual.contains(e)).map(|e| format!("{SNAPSHOT}: verify.assets lacks {e}"));
    let extra = actual.iter().filter(|a| !expected.contains(a)).map(|a| format!("{SNAPSHOT}: verify.assets has {a}, which no entry uploads"));
    missing.chain(extra).collect()
}
