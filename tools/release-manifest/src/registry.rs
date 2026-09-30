//! `probe` (ADR 29.9.26v D6): the URL whose 200 means "already published"
//! and whose 404 means "publish". The workflow fetches it; this prints it.

use crate::manifest::Release;
use crate::{Fail, version};

/// One URL per coordinate, or `None` for a registry with no per-version URL
/// (Packagist), which the CLI turns into exit 3.
pub fn probe(release: &Release, id: &str, tag: &str) -> Result<Option<Vec<String>>, Fail> {
    let lang = release.language(id)?;
    let semver = tag.strip_prefix('v').unwrap_or(tag);
    let package = &lang.package;
    let url = match lang.registry.as_str() {
        "npm" => format!("https://registry.npmjs.org/{package}/{semver}"),
        "crates.io" => format!("https://crates.io/api/v1/crates/{package}/{semver}"),
        "rubygems" => {
            format!("https://rubygems.org/api/v2/rubygems/{package}/versions/{}.json", version::gem_version(semver))
        }
        "maven-central" => maven_url(package, semver)?,
        "go" => format!("https://proxy.golang.org/{}/@v/{tag}.info", case_encode(package)),
        "packagist" => return Ok(None),
        other => return Err(Fail::input(format!("probe: {id}: unknown registry \"{other}\""))),
    };
    Ok(Some(vec![url]))
}

/// `group:artifact` → the version's POM on `repo1.maven.org`.
fn maven_url(package: &str, semver: &str) -> Result<String, Fail> {
    let (group, artifact) =
        package.split_once(':').ok_or_else(|| Fail::input(format!("probe: \"{package}\" is not group:artifact")))?;
    let group = group.replace('.', "/");
    Ok(format!("https://repo1.maven.org/maven2/{group}/{artifact}/{semver}/{artifact}-{semver}.pom"))
}

/// The module proxy's case encoding: each capital becomes `!` + its lowercase.
pub fn case_encode(module: &str) -> String {
    module
        .chars()
        .flat_map(|c| if c.is_ascii_uppercase() { vec!['!', c.to_ascii_lowercase()] } else { vec![c] })
        .collect()
}
