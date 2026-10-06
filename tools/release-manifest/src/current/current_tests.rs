use std::path::Path;

use tempfile::TempDir;

use crate::current::{REGISTRY, current_id, run};
use crate::manifest_tests::write;

/// The fixture registry `spec-codegen`'s agreement test reads too.
const FIXTURE: &str = include_str!("../../fixtures/current_registry.toml");

/// The fixture's current entry: its newest `lts`, past a newer `deprecated`.
const CURRENT: &str = "2026-09-steady-heron";

/// A tree whose `spec/versions.toml` is the fixture.
fn tree() -> TempDir {
    let dir = tempfile::tempdir().unwrap();
    write(dir.path(), REGISTRY, FIXTURE);
    dir
}

fn answer(root: &Path, json: &str) {
    write(root, "versions.json", json);
}

fn live(root: &Path, current: &str) {
    answer(root, &format!(r#"{{"current": "{current}", "development": "2026-10-fresh-otter", "versions": []}}"#));
}

#[test]
fn the_fixture_current_is_the_newest_pinnable_entry() {
    let table: toml::Table = FIXTURE.parse().unwrap();
    assert_eq!(current_id(&table).as_deref(), Some(CURRENT));
}

// 6.10.26i AC4
#[test]
fn the_same_version_passes() {
    let dir = tree();
    live(dir.path(), CURRENT);
    assert_eq!(run(dir.path(), &["versions.json"]).unwrap(), format!("✓ generated for {CURRENT}, the API's current version\n"));
}

// 6.10.26i AC1
#[test]
fn a_live_version_the_registry_lacks_refuses() {
    let dir = tree();
    live(dir.path(), "2026-10-affable-towhee");
    let fail = run(dir.path(), &["versions.json"]).unwrap_err();
    assert_eq!(fail.code, 1);
    for named in ["2026-10-affable-towhee", CURRENT, "make sync-api-spec"] {
        assert!(fail.message.contains(named), "{named} in {}", fail.message);
    }
}

// 6.10.26i AC2
#[test]
fn a_live_version_the_registry_holds_as_development_refuses() {
    let dir = tree();
    live(dir.path(), "2026-10-fresh-otter");
    let fail = run(dir.path(), &["versions.json"]).unwrap_err();
    assert_eq!(fail.code, 1);
    for named in ["2026-10-fresh-otter", CURRENT, "make sync-api-spec"] {
        assert!(fail.message.contains(named), "{named} in {}", fail.message);
    }
}

// 6.10.26i AC3
#[test]
fn a_registry_ahead_of_the_deployed_api_refuses() {
    let dir = tree();
    live(dir.path(), "2026-08-quiet-moth");
    let fail = run(dir.path(), &["versions.json"]).unwrap_err();
    assert_eq!(fail.code, 1);
    for named in ["2026-08-quiet-moth", CURRENT, "not deployed", "up to an hour old"] {
        assert!(fail.message.contains(named), "{named} in {}", fail.message);
    }
    assert!(!fail.message.contains("sync-api-spec"), "{}", fail.message);
}

// 6.10.26i AC5
#[test]
fn an_answer_without_current_refuses() {
    let dir = tree();
    let cases = [
        ("<html>502</html>", "not JSON"),
        (r#"{"development": "x"}"#, "no `current`"),
        (r#"{"current": null}"#, "no `current`"),
        (r#"{"current": 7}"#, "not a string"),
    ];
    for (json, missing) in cases {
        answer(dir.path(), json);
        let fail = run(dir.path(), &["versions.json"]).unwrap_err();
        assert_eq!(fail.code, 2, "{json}");
        assert!(fail.message.contains("GET /v1/versions"), "{}", fail.message);
        assert!(fail.message.contains(missing), "{missing} in {}", fail.message);
    }
}

// 6.10.26i AC6
#[test]
fn the_registry_path_is_an_argument() {
    let dir = tree();
    let other = FIXTURE.replace("state = \"development\"", "state = \"supported\"");
    write(dir.path(), "kit/versions.toml", &other);
    live(dir.path(), "2026-10-fresh-otter");
    assert_eq!(run(dir.path(), &["versions.json"]).unwrap_err().code, 1, "default: spec/versions.toml");
    assert!(run(dir.path(), &["--registry", "kit/versions.toml", "versions.json"]).is_ok(), "--registry reads the named file");
    assert_eq!(run(dir.path(), &["--registry", "kit/versions.toml"]).unwrap_err().code, 2, "no answer file");
}

// 6.10.26i AC7
#[test]
fn a_registry_without_a_pinnable_version_refuses() {
    let dir = tree();
    write(dir.path(), "dev-only.toml", "[[version]]\nid = \"2026-10-fresh-otter\"\nminted_at = \"2026-10-01T09:00:00Z\"\nstate = \"development\"\n");
    live(dir.path(), CURRENT);
    let fail = run(dir.path(), &["--registry", "dev-only.toml", "versions.json"]).unwrap_err();
    assert_eq!(fail.code, 2);
    assert!(fail.message.contains("dev-only.toml"), "{}", fail.message);
    assert!(fail.message.contains("no supported or lts"), "{}", fail.message);
}
