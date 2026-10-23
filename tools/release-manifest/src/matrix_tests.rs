use serde_json::{Value, json};

use crate::manifest_tests::{fixture, load, write};
use crate::matrix::matrix;

// 29.9.26v AC7
#[test]
fn matrix_lists_only_shipped_languages() {
    let dir = fixture();
    let jvm = |id: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"maven\"\npackage = \"com.getlingara:lingara-{id}\"\nsince = \"next\"\n")
    };
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("{text}{}{}", jvm("java"), jvm("kotlin")));

    let out = matrix(&load(&dir), "v0.1.0-alpha.1");
    let lines: Vec<&str> = out.lines().collect();
    assert_eq!(lines.len(), 2);
    let parsed: Value = serde_json::from_str(lines[0].strip_prefix("matrix=").unwrap()).unwrap();
    let ids: Vec<&str> = parsed["include"].as_array().unwrap().iter().map(|e| e["id"].as_str().unwrap()).collect();
    assert_eq!(ids, ["typescript", "rust", "java", "kotlin"]);
    assert_eq!(
        parsed["include"][0],
        json!({"id": "typescript", "registry": "npm", "package": "@lingara/api",
               "built": "typescript/lingara-api-0.1.0-alpha.1.tgz", "uploaded": "lingara-typescript-v0.1.0-alpha.1.tgz",
               "dir": "typescript", "manual": false})
    );
    assert_eq!(parsed["include"][2]["uploaded"], "", "an entry without an artefact uploads nothing");
    let registries: Value = serde_json::from_str(lines[1].strip_prefix("registries=").unwrap()).unwrap();
    assert_eq!(registries, json!(["npm", "crates.io", "maven"]));
}

/// The `include` rows of `matrix`'s output.
fn rows(out: &str) -> Vec<Value> {
    let parsed: Value = serde_json::from_str(out.lines().next().unwrap().strip_prefix("matrix=").unwrap()).unwrap();
    parsed["include"].as_array().unwrap().clone()
}

/// The CLI's `go` entry shape: a list `uploaded` and a `binary`.
const GO: &str = r#"
[[language]]
id = "go"
registry = "go"
package = "m"
artefact = { built = "dist", uploaded = ["lingara_{version}_darwin_amd64.tar.gz", "lingara_{version}_linux_arm64.tar.gz", "SHA256SUMS"] }
since = "next"
binary = "lingara"
"#;

// 22.10.26r AC1
#[test]
fn a_listed_upload_is_one_space_joined_string() {
    let dir = fixture();
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("{text}{GO}"));
    let rows = rows(&matrix(&load(&dir), "v0.1.0-alpha.1"));
    assert_eq!(
        rows[2]["uploaded"],
        "lingara_v0.1.0-alpha.1_darwin_amd64.tar.gz lingara_v0.1.0-alpha.1_linux_arm64.tar.gz SHA256SUMS"
    );
    assert_eq!(rows[2]["built"], "dist");
}

// 22.10.26r AC2
#[test]
fn a_file_without_the_widenings_keeps_its_rows() {
    let dir = fixture();
    // Captured from the tool before 22.10.26r, on the same fixture and tag.
    let before = "matrix={\"include\":[{\"id\":\"typescript\",\"registry\":\"npm\",\"package\":\"@lingara/api\",\"built\":\"typescript/lingara-api-0.1.0-alpha.1.tgz\",\"uploaded\":\"lingara-typescript-v0.1.0-alpha.1.tgz\",\"dir\":\"typescript\",\"manual\":false},{\"id\":\"rust\",\"registry\":\"crates.io\",\"package\":\"lingara\",\"built\":\"target/package/lingara-0.1.0-alpha.1.crate\",\"uploaded\":\"lingara-rust-v0.1.0-alpha.1.crate\",\"dir\":\"rust\",\"manual\":false}]}\nregistries=[\"npm\",\"crates.io\"]\n";
    assert_eq!(matrix(&load(&dir), "v0.1.0-alpha.1"), before);
}

// 22.10.26r AC11
#[test]
fn binary_is_in_the_row_only_when_set() {
    let dir = fixture();
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("{text}{GO}"));
    let rows = rows(&matrix(&load(&dir), "v0.1.0-alpha.1"));
    let keys: Vec<&String> = rows[2].as_object().unwrap().keys().collect();
    assert_eq!(keys.last().unwrap().as_str(), "binary", "{keys:?}");
    assert_eq!(rows[2]["binary"], "lingara");
    assert!(rows[0].get("binary").is_none(), "{}", rows[0]);
}

// 1.10.26ag AC6
#[test]
fn store_entries_are_marked_manual() {
    let dir = fixture();
    let entry = |id: &str, registry: &str, extra: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"{registry}\"\npackage = \"p\"\n{extra}since = \"next\"\n")
    };
    let extra = [
        entry("godot", "godot-assetlib", ""),
        entry("unreal", "fab", "dir = \"unreal-plugin\"\n"),
        entry("unity", "nuget", "dir = \"dotnet\"\n"),
    ];
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("registries = [\"npm\", \"crates.io\", \"nuget\", \"godot-assetlib\", \"fab\"]\n{text}{}", extra.concat()));

    let out = matrix(&load(&dir), "v0.1.0-alpha.1");
    let parsed: Value = serde_json::from_str(out.lines().next().unwrap().strip_prefix("matrix=").unwrap()).unwrap();
    let rows: Vec<(&str, &str, bool)> = parsed["include"]
        .as_array()
        .unwrap()
        .iter()
        .map(|e| (e["id"].as_str().unwrap(), e["dir"].as_str().unwrap(), e["manual"].as_bool().unwrap()))
        .collect();
    assert_eq!(
        rows,
        [
            ("typescript", "typescript", false),
            ("rust", "rust", false),
            ("godot", "godot", true),
            ("unreal", "unreal-plugin", true),
            ("unity", "dotnet", false),
        ]
    );
}
