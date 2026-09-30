//! Reading case files: the id their path fixes, and the whole directory.

use std::path::{Path, PathBuf};

use serde_json::Value;

use super::{Case, GROUPS};

/// A parsed case, and the same YAML as JSON: `GET /__conformance/cases/{id}`
/// serves `raw`, so what a harness reads is exactly what the file says.
#[derive(Debug, Clone)]
pub struct Loaded {
    pub case: Case,
    pub raw: Value,
}

/// Parses one case file's text; `rel` is its path under the cases root
/// (`k5/vocab-split-frames.yaml`), which fixes the id.
pub fn parse(yaml: &str, rel: &Path) -> Result<Loaded, String> {
    let where_ = rel.display();
    let case: Case = serde_yaml::from_str(yaml).map_err(|e| format!("{where_}: {e}"))?;
    let raw: Value = serde_yaml::from_str(yaml).map_err(|e| format!("{where_}: {e}"))?;
    let expected = id_for(rel).ok_or_else(|| format!("{where_}: not <group>/<slug>.yaml"))?;
    if case.id != expected {
        return Err(format!("{where_}: id `{}` should be `{expected}`", case.id));
    }
    super::check::validate(&case).map_err(|e| format!("{where_}: {e}"))?;
    Ok(Loaded { case, raw })
}

/// `k5/vocab-split-frames.yaml` → `k5.vocab-split-frames`, or `None` when
/// the path is not a known group's direct child.
pub fn id_for(rel: &Path) -> Option<String> {
    let mut parts = rel.iter();
    let group = parts.next()?.to_str()?;
    let file = Path::new(parts.next()?);
    if parts.next().is_some() || !GROUPS.contains(&group) {
        return None;
    }
    if file.extension()?.to_str()? != "yaml" {
        return None;
    }
    Some(format!("{group}.{}", file.file_stem()?.to_str()?))
}

/// Every case under `root`, sorted by id, or every file that failed.
pub fn load_dir(root: &Path) -> (Vec<Loaded>, Vec<String>) {
    let mut cases = Vec::new();
    let mut errors = Vec::new();
    for rel in yaml_files(root, &mut errors) {
        match std::fs::read_to_string(root.join(&rel)) {
            Ok(text) => match parse(&text, &rel) {
                Ok(loaded) => cases.push(loaded),
                Err(e) => errors.push(e),
            },
            Err(e) => errors.push(format!("{}: {e}", rel.display())),
        }
    }
    cases.sort_by(|a, b| a.case.id.cmp(&b.case.id));
    (cases, errors)
}

fn yaml_files(root: &Path, errors: &mut Vec<String>) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let Ok(groups) = std::fs::read_dir(root) else {
        errors.push(format!("{}: cannot read the cases directory", root.display()));
        return out;
    };
    for group in groups.flatten().filter(|g| g.path().is_dir()) {
        for file in std::fs::read_dir(group.path()).into_iter().flatten().flatten() {
            let rel = Path::new(&group.file_name()).join(file.file_name());
            out.push(rel);
        }
    }
    out.sort();
    out
}
