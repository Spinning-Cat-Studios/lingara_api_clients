//! `matrix` (ADR 29.9.26v D2, D4): what `release.yml` fans out over.

use serde_json::json;

use crate::install;
use crate::manifest::Release;

/// Two `$GITHUB_OUTPUT` lines: `matrix=` (one `include` row per entry, both
/// artefact names resolved for `tag`, empty where there is none) and
/// `registries=` (the distinct registries, in file order).
pub fn matrix(release: &Release, tag: &str) -> String {
    let include: Vec<_> = release
        .languages
        .iter()
        .map(|lang| {
            let (built, uploaded) = install::resolve(release, lang, tag).unwrap_or_default();
            json!({ "id": lang.id, "registry": lang.registry, "package": lang.package, "built": built, "uploaded": uploaded })
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
