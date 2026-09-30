//! `langs`, `artefact` and `install-line` (ADR 29.9.26v D2, D8).

use std::fs;
use std::path::{Path, PathBuf};

use crate::Fail;
use crate::manifest::{Language, Release};

/// The released-language list: the ids, space-separated, in file order.
pub fn langs(release: &Release) -> String {
    release.ids().join(" ")
}

/// `(built, uploaded)` for `tag`: `{semver}` is `VERSION`, `{version}` the
/// tag with its `v`, as `verify.assets` substitutes it.
pub fn resolve(release: &Release, lang: &Language, tag: &str) -> Option<(String, String)> {
    let names = lang.artefact.as_ref()?;
    Some((names.built.replace("{semver}", &release.version), names.uploaded.replace("{version}", tag)))
}

pub fn artefact(release: &Release, id: &str, tag: &str) -> Result<Option<(String, String)>, Fail> {
    Ok(resolve(release, release.language(id)?, tag))
}

/// Every `snippets/<id>/install.*`, sorted.
pub fn install_files(root: &Path, id: &str) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir(root.join("snippets").join(id)) else { return Vec::new() };
    let mut files: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with("install.")))
        .collect();
    files.sort();
    files
}

/// The install snippet with every `{{version}}` replaced by the tag's
/// `X.Y.Z`: the site's substitution, so the proof runs what the site shows.
pub fn install_line(release: &Release, id: &str, tag: &str) -> Result<String, Fail> {
    release.language(id)?;
    let files = install_files(&release.root, id);
    let [file] = files.as_slice() else {
        return Err(Fail::input(format!("snippets/{id}/: {} install.* files, exactly one expected", files.len())));
    };
    let text = fs::read_to_string(file).map_err(|e| Fail::input(format!("{}: {e}", file.display())))?;
    Ok(text.replace("{{version}}", tag.strip_prefix('v').unwrap_or(tag)))
}
