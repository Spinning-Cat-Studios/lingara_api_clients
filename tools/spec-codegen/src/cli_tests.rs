use std::fs;

use crate::cli::{run, OUTPUTS};
use crate::lift_tests::fixture;

fn args(parts: &[&str]) -> Vec<String> {
    parts.iter().map(|s| s.to_string()).collect()
}

// 29.9.26m AC9
#[test]
fn refusal_exits_2_and_check_catches_a_hand_edit() {
    let dir = tempfile::tempdir().unwrap();
    let path = |name: &str| dir.path().join(name).to_str().unwrap().to_owned();
    fs::write(path("spec.json"), fixture().to_string()).unwrap();
    fs::write(path("SOURCE"), "backend@0000000000000000000000000000000000000000\n").unwrap();
    let out = path("generator");
    let base = ["--input", &path("spec.json"), "--source", &path("SOURCE"), "--out-dir", &out];
    let check = [&base[..], &["--check"]].concat();

    assert_eq!(run(&args(&check)), 1, "--check before any write: both dialects differ");
    assert_eq!(run(&args(&base)), 0);
    assert_eq!(run(&args(&check)), 0);

    let dialect = dir.path().join("generator").join(OUTPUTS[1]);
    let edited = fs::read_to_string(&dialect).unwrap().replace("3.0.3", "3.0.2");
    fs::write(&dialect, edited).unwrap();
    assert_eq!(run(&args(&check)), 1, "a hand-edited dialect is caught");

    let mut refused = fixture();
    refused["openapi"] = "3.1.0".into();
    fs::write(path("spec.json"), refused.to_string()).unwrap();
    assert_eq!(run(&args(&base)), 2);
    assert_eq!(run(&args(&["--input"])), 2, "a flag without its value");
    assert_eq!(run(&args(&["--bogus", "x"])), 2);
}

/// A fixture registry: `(id, minted_at, state)` rows in mint order.
fn registry(rows: &[(&str, &str, &str)]) -> String {
    rows.iter()
        .map(|(id, at, state)| format!("[[version]]\nid = \"{id}\"\nminted_at = \"{at}\"\nstate = \"{state}\"\n\n"))
        .collect()
}

// 30.9.26a AC8
#[test]
fn registry_input_is_the_current_frozen_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let path = |name: &str| dir.path().join(name).to_str().unwrap().to_owned();
    fs::create_dir_all(dir.path().join("versions")).unwrap();
    for id in ["2026-09-old-lts", "2026-09-new-supported", "2026-09-newest-deprecated", "2026-09-open-dev"] {
        let mut snapshot = fixture();
        snapshot["info"]["version"] = id.into();
        fs::write(dir.path().join("versions").join(format!("{id}.openapi.json")), snapshot.to_string()).unwrap();
    }
    fs::write(path("SOURCE"), "backend@0000000000000000000000000000000000000000\n").unwrap();
    let rows = [
        ("2026-09-old-lts", "2026-09-01T00:00:00Z", "lts"),
        ("2026-09-new-supported", "2026-09-02T00:00:00Z", "supported"),
        ("2026-09-newest-deprecated", "2026-09-03T00:00:00Z", "deprecated"),
        ("2026-09-open-dev", "2026-09-04T00:00:00Z", "development"),
    ];
    fs::write(path("versions.toml"), registry(&rows)).unwrap();
    let out = path("generator");
    let base = ["--registry", &path("versions.toml"), "--source", &path("SOURCE"), "--out-dir", &out];

    assert_eq!(run(&args(&base)), 0);
    for name in OUTPUTS {
        let view: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(dir.path().join("generator").join(name)).unwrap()).unwrap();
        assert_eq!(view["info"]["version"], "2026-09-new-supported", "{name}: the newest supported/lts, never development");
    }

    fs::write(path("versions.toml"), registry(&rows[3..])).unwrap();
    assert_eq!(run(&args(&base)), 2, "a development-only registry has no current");
    fs::write(path("versions.toml"), "").unwrap();
    assert_eq!(run(&args(&base)), 2, "an empty registry has no current");

    let input = path("versions/2026-09-open-dev.openapi.json");
    let both = [&base[..], &["--input", &input]].concat();
    assert_eq!(run(&args(&both)), 2, "--registry with --input is refused");
}

// 30.9.26aa AC4
#[test]
fn an_events_route_without_a_catalogue_is_refused() {
    use crate::events::events_tests::{catalogue, openapi};
    let dir = tempfile::tempdir().unwrap();
    let path = |name: &str| dir.path().join(name).to_str().unwrap().to_owned();
    let versions = dir.path().join("versions");
    fs::create_dir_all(&versions).unwrap();
    fs::write(path("SOURCE"), "backend@0000000000000000000000000000000000000000\n").unwrap();
    let id = "2026-09-events-pair";
    let row = |hash: bool| {
        let extra = if hash { "asyncapi_sha256 = \"00\"\n" } else { "" };
        format!("[[version]]\nid = \"{id}\"\nminted_at = \"2026-09-01T00:00:00Z\"\nstate = \"supported\"\n{extra}")
    };
    let out = path("generator");
    let base = ["--registry", &path("versions.toml"), "--source", &path("SOURCE"), "--out-dir", &out];
    let snapshot = versions.join(format!("{id}.openapi.json"));
    let catalogue_file = versions.join(format!("{id}.asyncapi.json"));

    // An OpenAPI snapshot with /v1/events and no AsyncAPI snapshot.
    fs::write(&snapshot, openapi().to_string()).unwrap();
    fs::write(path("versions.toml"), row(false)).unwrap();
    assert_eq!(run(&args(&base)), 2, "events routes with no catalogue are refused");

    // The pair, read from the registry.
    fs::write(&catalogue_file, catalogue().to_string()).unwrap();
    assert_eq!(run(&args(&base)), 0, "the pair builds");
    let view: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(dir.path().join("generator").join(OUTPUTS[0])).unwrap()).unwrap();
    assert_eq!(view["x-lingara-events"].as_array().unwrap().len(), 3);

    // A current entry naming asyncapi_sha256 whose snapshot is missing.
    fs::write(path("versions.toml"), row(true)).unwrap();
    fs::remove_file(&catalogue_file).unwrap();
    assert_eq!(run(&args(&base)), 2, "a hash with no snapshot is refused");

    // A pair with neither: an empty catalogue, written as none at all.
    fs::write(&snapshot, fixture().to_string()).unwrap();
    fs::write(path("versions.toml"), row(false)).unwrap();
    assert_eq!(run(&args(&base)), 0, "no events route and no catalogue builds");
    let view: serde_json::Value =
        serde_json::from_str(&fs::read_to_string(dir.path().join("generator").join(OUTPUTS[0])).unwrap()).unwrap();
    assert!(view.get("x-lingara-events").is_none());

    // --input takes the catalogue as --asyncapi; --asyncapi alone is refused.
    let nope = path("nope.json");
    let input = [&["--input", snapshot.to_str().unwrap(), "--asyncapi", &nope][..], &base[2..]].concat();
    assert_eq!(run(&args(&input)), 2, "an unreadable --asyncapi is refused");
    let lone = [&["--asyncapi", &nope][..], &base[..]].concat();
    assert_eq!(run(&args(&lone)), 2, "--asyncapi goes with --input only");
}
