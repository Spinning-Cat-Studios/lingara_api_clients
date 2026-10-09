use serde_json::{Value, json};

use crate::manifest_tests::{fixture, load, write};
use crate::matrix::matrix;

// 29.9.26v AC7
#[test]
fn matrix_lists_only_shipped_languages() {
    let dir = fixture();
    let jvm = |id: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"maven-central\"\npackage = \"com.getlingara:lingara-{id}\"\nsince = \"next\"\n")
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
    assert_eq!(registries, json!(["npm", "crates.io", "maven-central"]));
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
