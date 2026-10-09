//! `matrix` (ADR 29.9.26v D2, D4): what `release.yml` fans out over.

use serde_json::json;

use crate::install;
use crate::manifest::Release;

/// Two `$GITHUB_OUTPUT` lines: `matrix=` (one `include` row per entry, both
/// artefact names resolved for `tag`, empty where there is none) and
/// `registries=` (the distinct registries, in file order). 1.10.26ag adds
/// each row's `dir` (W2), where its `build-<id>` leg works, and `manual`
/// (W4), true for a store, which `dry-run-<id>` reads.
pub fn matrix(release: &Release, tag: &str) -> String {
    let include: Vec<_> = release
        .languages
        .iter()
        .map(|lang| {
            let (built, uploaded) = install::resolve(release, lang, tag).unwrap_or_default();
            json!({ "id": lang.id, "registry": lang.registry, "package": lang.package, "built": built, "uploaded": uploaded,
                    "dir": lang.directory(), "manual": lang.is_store() })
        })
        .collect();
    let mut registries: Vec<&str> = Vec::new();
    for lang in &release.languages {
        if !registries.contains(&lang.registry.as_str()) {
            registries.push(&lang.registry);
        }
    }
    format!("matrix={}\nregistries={}\n", json!({ "include": include }), json!(registries))
}
