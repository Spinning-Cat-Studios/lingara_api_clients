//! One `[[language]]` entry's tree checks (ADR 29.9.26v D2), split from
//! `manifest` when ADR 22.10.26r's W8 and W9 findings joined them.

use super::{Artefact, LANGUAGES, Language, REGISTRIES, RELEASE_WORKFLOW, Release};
use crate::install;

pub fn entry_findings(release: &Release, lang: &Language, workflow: &str) -> Vec<String> {
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
        findings.extend(artefact_findings(id, a));
    }
    findings.extend(binary_finding(lang));
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

/// `built` and every `uploaded` name through `template_finding`. W8: a list
/// is never empty, and no name has a space, since `matrix` and `artefact`
/// join the names with one.
fn artefact_findings(id: &str, artefact: &Artefact) -> Vec<String> {
    let mut findings: Vec<String> = template_finding(id, "built", &artefact.built, "semver").into_iter().collect();
    let names = artefact.uploaded();
    if names.is_empty() {
        findings.push(format!("{LANGUAGES}: {id}: artefact.uploaded is an empty list"));
    }
    for name in names {
        findings.extend(template_finding(id, "uploaded", name, "version"));
        if name.contains(' ') {
            findings.push(format!("{LANGUAGES}: {id}: artefact.uploaded name \"{name}\" contains a space"));
        }
    }
    findings
}

/// `built` may use only `{semver}`, `uploaded` only `{version}` (D1).
fn template_finding(id: &str, field: &str, template: &str, allowed: &str) -> Option<String> {
    let placeholders = template.split('{').skip(1).filter_map(|s| s.split_once('}').map(|(p, _)| p));
    let bad: Vec<&str> = placeholders.filter(|p| *p != allowed).collect();
    (!bad.is_empty()).then(|| {
        format!("{LANGUAGES}: {id}: artefact.{field} uses {{{}}}; only {{{allowed}}} is allowed there", bad.join("}, {"))
    })
}

/// W9: a set `binary` is `[a-z][a-z0-9-]*`.
fn binary_finding(lang: &Language) -> Option<String> {
    let binary = lang.binary.as_deref()?;
    let mut chars = binary.chars();
    let well_formed = chars.next().is_some_and(|c| c.is_ascii_lowercase())
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    (!well_formed).then(|| format!("{LANGUAGES}: {}: binary \"{binary}\" is not [a-z][a-z0-9-]*", lang.id))
}
