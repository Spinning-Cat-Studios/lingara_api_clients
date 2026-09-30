use std::path::Path;
use std::process::Command;

use crate::changelog::{UNRELEASED, check_changelog, names, spec_line};
use crate::manifest_tests::{LANGUAGES, fixture, load, write};

const SOURCE: &str = "backend@0123456789abcdef0123456789abcdef01234567";

fn spec(root: &Path, info_version: &str) {
    write(root, "spec/openapi.json", &format!("{{\"info\": {{\"version\": \"{info_version}\"}}}}"));
    write(root, "spec/versions.toml", "[[version]]\nid = \"2026-09-affable-cat\"\nstate = \"supported\"\n");
    write(root, "spec/SOURCE", &format!("{SOURCE}\n"));
}

fn git(root: &Path, args: &[&str]) {
    let status = Command::new("git")
        .arg("-C")
        .arg(root)
        .args(["-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false"])
        .args(args)
        .status()
        .unwrap();
    assert!(status.success(), "git {args:?}");
}

// 29.9.26v AC5
#[test]
fn the_changelog_must_carry_the_spec_line() {
    let dir = fixture();
    let root = dir.path();
    spec(root, "2026-09-affable-cat");
    let line = format!("Generated from Lingara API 2026-09-affable-cat (supported) at spec {SOURCE}.");
    assert_eq!(spec_line(root).unwrap(), line);
    spec(root, "2026-10-unlisted-owl");
    let line = format!("Generated from Lingara API 2026-10-unlisted-owl (development) at spec {SOURCE}.");
    assert_eq!(spec_line(root).unwrap(), line);

    git(root, &["init", "-q"]);
    git(root, &["commit", "-q", "--allow-empty", "-m", "initial"]);
    let added = "### Added\n\n- The TypeScript library.\n- The Rust library.\n";
    write(root, UNRELEASED, added);
    let findings = check_changelog(&load(&dir)).unwrap();
    assert_eq!(findings.len(), 1, "{findings:?}");
    assert!(findings[0].contains("does not carry the spec line"));

    write(root, UNRELEASED, &format!("{line}\n\n{added}"));
    assert_eq!(check_changelog(&load(&dir)).unwrap(), Vec::<String>::new());
}

// 29.9.26v AC6
#[test]
fn a_new_language_needs_an_added_bullet() {
    let dir = fixture();
    let root = dir.path();
    spec(root, "2026-09-affable-cat");
    let line = spec_line(root).unwrap();
    git(root, &["init", "-q"]);
    git(root, &["add", "-A"]);
    git(root, &["commit", "-q", "-m", "changelog: promote unreleased -> v0.1.0-alpha.1"]);
    git(root, &["commit", "-q", "--allow-empty", "-m", "unrelated"]);

    let go = "\n[[language]]\nid = \"go\"\nregistry = \"go\"\npackage = \"m\"\nsince = \"next\"\n";
    write(root, "languages.toml", &format!("{LANGUAGES}{go}"));
    write(root, UNRELEASED, &format!("{line}\n\n### Added\n\n- A faster algorithm.\n\n### Fixed\n\n- The Go proxy.\n"));
    let findings = check_changelog(&load(&dir)).unwrap();
    assert_eq!(findings.len(), 1, "only go is new: {findings:?}");
    assert!(findings[0].contains("go is new since the last release"));

    write(root, UNRELEASED, &format!("{line}\n\n### Added\n\n- The Go library.\n"));
    assert_eq!(check_changelog(&load(&dir)).unwrap(), Vec::<String>::new());
}

#[test]
fn a_changelog_check_outside_publish_exits_2() {
    let dir = fixture();
    std::fs::remove_file(dir.path().join("publish/snapshot.toml")).unwrap();
    assert_eq!(check_changelog(&load(&dir)).unwrap_err().code, 2);
}

#[test]
fn names_matches_whole_words_only() {
    assert!(names("- The Go library", "go"));
    assert!(names("- go.", "go"));
    assert!(!names("- A faster algorithm", "go"));
    assert!(!names("- gopher", "go"));
}
