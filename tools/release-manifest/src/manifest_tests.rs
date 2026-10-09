use std::fs;
use std::path::Path;

use tempfile::TempDir;

use crate::manifest::{Release, check};
use crate::publish::check_publish;

pub const LICENCE: &str = "MIT License\n\nCopyright (c) 2026\n";

/// The two C3a entries, as the repository's own `languages.toml` writes them.
pub const LANGUAGES: &str = r#"[[language]]
id = "typescript"
registry = "npm"
package = "@lingara/api"
version_files = [
  { path = "typescript/package.json", kind = "json", keys = ["/version"] },
  { path = "typescript/package-lock.json", kind = "json", keys = ["/version", "/packages//version"] },
]
artefact = { built = "typescript/lingara-api-{semver}.tgz", uploaded = "lingara-typescript-{version}.tgz" }
since = "0.1.0-alpha.1"

[[language]]
id = "rust"
registry = "crates.io"
package = "lingara"
version_files = [{ path = "rust/Cargo.toml", kind = "toml", keys = ["package.version"] }]
artefact = { built = "target/package/lingara-{semver}.crate", uploaded = "lingara-rust-{version}.crate" }
since = "0.1.0-alpha.1"
"#;

pub const WORKFLOW: &str = "jobs:\n  publish-npm:\n  publish-crates:\n  publish-rubygems:\n  publish-maven:\n  publish-go:\n  publish-packagist:\n";

const SNAPSHOT: &str = r#"[[manifest]]
kind = "cargo"
path = "Cargo.toml"

[[manifest]]
kind = "npm"
path = "typescript/package.json"

[[manifest]]
kind = "cargo"
path = "rust/Cargo.toml"

[verify]
assets = ["lingara-typescript-{version}.tgz", "lingara-rust-{version}.crate", "checksums.txt"]
"#;

pub fn write(root: &Path, rel: &str, text: &str) {
    let path = root.join(rel);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    fs::write(path, text).unwrap();
}

/// A tree `check` and `check-publish` both pass, at VERSION 0.1.0-alpha.1.
pub fn fixture() -> TempDir {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path();
    let version = "0.1.0-alpha.1";
    write(root, "VERSION", &format!("{version}\n"));
    write(root, "LICENSE", LICENCE);
    write(root, "languages.toml", LANGUAGES);
    write(root, ".github/workflows/release.yml", WORKFLOW);
    write(root, "typescript/package.json", &format!("{{\n  \"name\": \"@lingara/api\",\n  \"version\": \"{version}\"\n}}\n"));
    let lock = format!("{{\n  \"version\": \"{version}\",\n  \"packages\": {{\n    \"\": {{\n      \"version\": \"{version}\"\n    }}\n  }}\n}}\n");
    write(root, "typescript/package-lock.json", &lock);
    write(root, "typescript/LICENSE", LICENCE);
    write(root, "rust/Cargo.toml", &format!("[package]\nname = \"lingara\"\nversion = \"{version}\" # lockstep\n"));
    write(root, "rust/LICENSE", LICENCE);
    write(root, "snippets/typescript/install.sh", "npm install @lingara/api@{{version}}\n");
    write(root, "snippets/rust/install.sh", "cargo add lingara@{{version}}\n");
    write(root, "publish/snapshot.toml", SNAPSHOT);
    write(root, "publish/public.allowlist", "# comment\ntypescript/\nsnippets/typescript/\nrust/\nsnippets/\n");
    dir
}

pub fn load(dir: &TempDir) -> Release {
    Release::load(dir.path()).unwrap()
}

fn has(findings: &[String], needle: &str) -> bool {
    findings.iter().any(|f| f.contains(needle))
}

#[test]
fn the_fixture_passes_both_checks() {
    let dir = fixture();
    assert_eq!(check(&load(&dir)), Vec::<String>::new());
    assert_eq!(check_publish(&load(&dir)).unwrap(), Vec::<String>::new());
}

// 29.9.26v AC1
#[test]
fn check_publish_refuses_each_disagreement_by_name() {
    let dir = fixture();
    let root = dir.path();
    write(root, "publish/public.allowlist", "typescript/\n");
    let assets = SNAPSHOT.replace("\"lingara-rust-{version}.crate\", ", "\"extra.zip\", ");
    write(root, "publish/snapshot.toml", &assets);
    let findings = check_publish(&load(&dir)).unwrap();
    assert!(has(&findings, "snippets/typescript/ is not covered"), "{findings:?}");
    assert!(has(&findings, "rust/ is not covered"), "{findings:?}");
    assert!(has(&findings, "snippets/rust/ is not covered"), "{findings:?}");
    assert!(has(&findings, "verify.assets lacks lingara-rust-{version}.crate"), "{findings:?}");
    assert!(has(&findings, "verify.assets has extra.zip"), "{findings:?}");

    fs::remove_file(root.join("publish/snapshot.toml")).unwrap();
    let fail = check_publish(&load(&dir)).unwrap_err();
    assert_eq!(fail.code, 2);
    assert!(fail.message.contains("publish/snapshot.toml"), "{}", fail.message);
}

// 29.9.26v AC14
#[test]
fn check_refuses_a_malformed_entry() {
    let dir = fixture();
    let root = dir.path();
    let go = "\n[[language]]\nid    = \"go\"\nregistry = \"go\"\npackage = \"m\"\nsince = \"next\"\n";
    let bad = "\n[[language]]\nid = \"ruby\"\nregistry = \"gems\"\npackage = \"lingara\"\nsince = \"next\"\n";
    let template = LANGUAGES.replace("lingara-api-{semver}", "lingara-api-{version}").replace("rust-{version}", "rust-{tag}");
    write(root, "languages.toml", &format!("{template}{go}{bad}"));
    write(root, ".github/workflows/release.yml", &WORKFLOW.replace("  publish-go:\n", ""));
    write(root, "go/LICENSE", "not MIT\n");
    write(root, "ruby/LICENSE", LICENCE);
    write(root, "snippets/go/install.sh", "go get m@v{{version}}\n");
    write(root, "snippets/rust/install.xml", "<dependency/>\n");
    fs::remove_file(root.join("rust/LICENSE")).unwrap();
    std::os::unix::fs::symlink("../LICENSE", root.join("rust/LICENSE")).unwrap();

    let findings = check(&load(&dir));
    assert!(has(&findings, "registry \"gems\" is not one of"), "{findings:?}");
    assert!(has(&findings, "no `publish-go` job"), "{findings:?}");
    assert!(has(&findings, "id \"go\" is not written as its own line"), "{findings:?}");
    assert!(has(&findings, "artefact.built uses {version}"), "{findings:?}");
    assert!(has(&findings, "artefact.uploaded uses {tag}"), "{findings:?}");
    assert!(has(&findings, "snippets/rust/: 2 install.* files"), "{findings:?}");
    assert!(has(&findings, "snippets/ruby/: 0 install.* files"), "{findings:?}");
    assert!(has(&findings, "go/LICENSE: differs from the root LICENSE"), "{findings:?}");
    assert!(!has(&findings, "rust/LICENSE"), "crates.io follows a symlink: {findings:?}");

    fs::remove_file(root.join("go/LICENSE")).unwrap();
    std::os::unix::fs::symlink("../LICENSE", root.join("go/LICENSE")).unwrap();
    assert!(has(&check(&load(&dir)), "go/LICENSE: a symlink; go needs a regular file"));
}

// 29.9.26v AC15
#[test]
fn an_entry_is_answered_by_exactly_one_manifest() {
    let dir = fixture();
    let root = dir.path();
    let jvm = |id: &str| format!("\n[[language]]\nid = \"{id}\"\nregistry = \"maven-central\"\npackage = \"com.getlingara:lingara-{id}\"\nmanifest = \"settings.gradle.kts\"\nsince = \"next\"\n");
    write(root, "languages.toml", &format!("{LANGUAGES}{}{}", jvm("java"), jvm("kotlin")));
    for id in ["java", "kotlin"] {
        write(root, &format!("{id}/README.md"), "");
        write(root, &format!("snippets/{id}/install.kts"), "");
    }
    write(root, "publish/public.allowlist", "typescript/\nrust/\njava/\nkotlin/\nsnippets/\n");
    let gradle = "\n[[manifest]]\nkind = \"gradle\"\npath = \"settings.gradle.kts\"\n";
    write(root, "publish/snapshot.toml", &format!("{SNAPSHOT}{gradle}"));
    assert_eq!(check_publish(&load(&dir)).unwrap(), Vec::<String>::new(), "Java and Kotlin share the root");

    let second = "\n[[manifest]]\nkind = \"npm\"\npath = \"typescript/other/package.json\"\n";
    let none = SNAPSHOT.replace("path = \"rust/Cargo.toml\"", "path = \"elsewhere/Cargo.toml\"");
    write(root, "publish/snapshot.toml", &format!("{none}{second}"));
    let findings = check_publish(&load(&dir)).unwrap();
    assert!(has(&findings, "2 [[manifest]] entries under typescript/"), "{findings:?}");
    assert!(has(&findings, "0 [[manifest]] entries under rust/"), "{findings:?}");
    assert!(has(&findings, "java names manifest settings.gradle.kts, which no [[manifest]] has"), "{findings:?}");
}

/// 1.10.26ag: the fixture's workflow with W1's three publish jobs beside the six.
fn trio_workflow(root: &Path) {
    write(root, ".github/workflows/release.yml", &format!("{WORKFLOW}  publish-nuget:\n  publish-godot-assetlib:\n  publish-fab:\n"));
}

/// One `[[language]]` table: `extra` is written between `package` and `since`.
fn entry(id: &str, registry: &str, extra: &str) -> String {
    format!("\n[[language]]\nid = \"{id}\"\nregistry = \"{registry}\"\npackage = \"Lingara.Embed\"\n{extra}since = \"next\"\n")
}

// 1.10.26ag AC1
#[test]
fn a_file_admits_only_the_registries_it_declares() {
    let dir = fixture();
    let root = dir.path();
    trio_workflow(root);
    write(root, "dotnet/README.md", "");
    write(root, "snippets/unity/install.sh", "dotnet add package Lingara.Embed\n");
    let unity = entry("unity", "nuget", "dir = \"dotnet\"\n");

    // Without `registries`: the original six, so the clients' own file still refuses nuget.
    write(root, "languages.toml", &format!("{LANGUAGES}{unity}"));
    let findings = check(&load(&dir));
    assert!(
        findings.contains(&"languages.toml: unity: registry \"nuget\" is not one of npm, crates.io, rubygems, maven-central, go, packagist".to_string()),
        "{findings:?}"
    );

    // Declared: exactly those, and nuget passes.
    write(root, "languages.toml", &format!("registries = [\"npm\", \"crates.io\", \"nuget\"]\n{LANGUAGES}{unity}"));
    assert_eq!(check(&load(&dir)), Vec::<String>::new());

    // An entry outside the declared set, and an unknown id in the set itself.
    write(root, "languages.toml", &format!("registries = [\"npm\", \"nuget\", \"pypi\"]\n{LANGUAGES}{unity}"));
    let findings = check(&load(&dir));
    assert!(has(&findings, "rust: registry \"crates.io\" is not one of npm, nuget"), "{findings:?}");
    assert!(has(&findings, "registries names \"pypi\", which is not one of npm, crates.io"), "{findings:?}");
    assert!(!has(&findings, "unity: registry"), "{findings:?}");
}

// 1.10.26ag AC2
#[test]
fn dir_and_snippets_relocate_an_entry() {
    let dir = fixture();
    let root = dir.path();
    trio_workflow(root);
    let unity = entry("unity", "nuget", "dir = \"dotnet\"\nsnippets = \"snippets/player/unity\"\n");
    let server = entry("csharp", "nuget", "dir = \"dotnet\"\nsnippets = \"snippets/server/csharp\"\n");
    write(root, "languages.toml", &format!("registries = [\"npm\", \"crates.io\", \"nuget\"]\n{LANGUAGES}{unity}{server}"));
    write(root, "dotnet/Lingara.Embed.csproj", "");
    write(root, "snippets/player/unity/install.sh", "dotnet add package Lingara.Embed\n");
    write(root, "snippets/server/csharp/install.sh", "dotnet add package Lingara.Embed.Server\n");
    write(root, "publish/public.allowlist", "typescript/\nrust/\ndotnet/\nsnippets/\n");
    assert_eq!(check(&load(&dir)), Vec::<String>::new(), "both entries resolve to dotnet/; no unity/ or csharp/");
    assert_eq!(check_publish(&load(&dir)).unwrap(), Vec::<String>::new());

    // The snippets key replaces snippets/<id>/ in `check`…
    fs::remove_file(root.join("snippets/server/csharp/install.sh")).unwrap();
    let findings = check(&load(&dir));
    assert_eq!(findings, vec!["snippets/server/csharp/: 0 install.* files, exactly one expected".to_string()]);
    write(root, "snippets/server/csharp/install.sh", "dotnet add package Lingara.Embed.Server\n");

    // …and in check-publish's allowlist rule.
    write(root, "publish/public.allowlist", "typescript/\nrust/\ndotnet/\nsnippets/typescript/\nsnippets/rust/\nsnippets/player/\n");
    let findings = check_publish(&load(&dir)).unwrap();
    assert_eq!(findings, vec!["publish/public.allowlist: snippets/server/csharp/ is not covered, so it would not ship".to_string()]);

    fs::remove_dir_all(root.join("dotnet")).unwrap();
    assert_eq!(check(&load(&dir)).iter().filter(|f| *f == "dotnet/: the directory does not exist").count(), 2);
}

// 1.10.26ag AC3
#[test]
fn store_and_nuget_entries_skip_directory_rules() {
    let dir = fixture();
    let root = dir.path();
    trio_workflow(root);
    let entries = [
        entry("godot", "godot-assetlib", ""),
        entry("unreal", "fab", ""),
        entry("unity", "nuget", "dir = \"dotnet\"\n"),
    ];
    let declared = "registries = [\"npm\", \"crates.io\", \"rubygems\", \"nuget\", \"godot-assetlib\", \"fab\"]\n";
    write(root, "languages.toml", &format!("{declared}{LANGUAGES}{}", entries.concat()));
    // No LICENSE in any of the three directories, no [[manifest]], and no install.* for the two stores.
    for d in ["godot", "unreal", "dotnet"] {
        write(root, &format!("{d}/README.md"), "");
    }
    write(root, "snippets/unity/install.sh", "dotnet add package Lingara.Embed\n");
    write(root, "publish/public.allowlist", "typescript/\nrust/\ngodot/\nunreal/\ndotnet/\nsnippets/\n");
    assert_eq!(check(&load(&dir)), Vec::<String>::new());
    assert_eq!(check_publish(&load(&dir)).unwrap(), Vec::<String>::new());

    // NuGet is not a store: it still proves its one install line.
    fs::remove_file(root.join("snippets/unity/install.sh")).unwrap();
    assert_eq!(check(&load(&dir)), vec!["snippets/unity/: 0 install.* files, exactly one expected".to_string()]);
    write(root, "snippets/unity/install.sh", "dotnet add package Lingara.Embed\n");

    // The six keep every rule: a ruby entry beside them needs its LICENSE, its install line and its manifest.
    let ruby = "\n[[language]]\nid = \"ruby\"\nregistry = \"rubygems\"\npackage = \"lingara-embed\"\nsince = \"next\"\n";
    write(root, "languages.toml", &format!("{declared}{LANGUAGES}{}{ruby}", entries.concat()));
    write(root, "ruby/README.md", "");
    write(root, "publish/public.allowlist", "typescript/\nrust/\ngodot/\nunreal/\ndotnet/\nruby/\nsnippets/\n");
    let findings = check_publish(&load(&dir)).unwrap();
    assert!(has(&findings, "ruby/LICENSE: missing"), "{findings:?}");
    assert!(has(&findings, "snippets/ruby/: 0 install.* files"), "{findings:?}");
    assert!(has(&findings, "0 [[manifest]] entries under ruby/"), "{findings:?}");
    assert_eq!(findings.len(), 3, "{findings:?}");
}

// 1.10.26ag AC4
#[test]
fn extra_assets_join_the_verify_set() {
    let dir = fixture();
    let root = dir.path();
    let zip = "lingara-embed-ffi-{version}-win-x64.zip";
    write(root, "languages.toml", &format!("extra_assets = [\"{zip}\"]\n{LANGUAGES}"));
    let findings = check_publish(&load(&dir)).unwrap();
    assert_eq!(findings, vec![format!("publish/snapshot.toml: verify.assets lacks {zip}")]);

    let with_zip = SNAPSHOT.replace("\"checksums.txt\"", &format!("\"{zip}\", \"checksums.txt\""));
    write(root, "publish/snapshot.toml", &with_zip);
    assert_eq!(check_publish(&load(&dir)).unwrap(), Vec::<String>::new());

    write(root, "publish/snapshot.toml", &with_zip.replace("\"checksums.txt\"", "\"other.zip\", \"checksums.txt\""));
    let findings = check_publish(&load(&dir)).unwrap();
    assert_eq!(
        findings,
        vec!["publish/snapshot.toml: verify.assets has other.zip, which no entry uploads".to_string()]
    );
}
