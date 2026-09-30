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
