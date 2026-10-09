use std::fs;

use crate::install::{artefact, install_line, langs};
use crate::manifest_tests::{fixture, load, write};

const TAG: &str = "v0.1.0-alpha.1";

// 29.9.26v AC16
#[test]
fn artefact_names_resolve_semver_and_tag() {
    let dir = fixture();
    let release = load(&dir);
    let (built, uploaded) = artefact(&release, "typescript", TAG).unwrap().unwrap();
    assert_eq!(built, "typescript/lingara-api-0.1.0-alpha.1.tgz");
    assert_eq!(uploaded, "lingara-typescript-v0.1.0-alpha.1.tgz");
    assert_eq!(langs(&release), "typescript rust");
    assert_eq!(artefact(&release, "cobol", TAG).unwrap_err().code, 2);
}

// 29.9.26v AC17
#[test]
fn install_line_is_c5s_substitution() {
    let dir = fixture();
    let root = dir.path();
    let go = "\n[[language]]\nid = \"go\"\nregistry = \"go\"\npackage = \"m\"\nsince = \"next\"\n";
    let text = fs::read_to_string(root.join("languages.toml")).unwrap();
    write(root, "languages.toml", &format!("{text}{go}"));
    write(root, "snippets/go/install.sh", "go get m@v{{version}}\n# {{version}} again\n");
    assert_eq!(install_line(&load(&dir), "go", TAG).unwrap(), "go get m@v0.1.0-alpha.1\n# 0.1.0-alpha.1 again\n");

    write(root, "snippets/go/install.xml", "<dependency/>\n");
    assert!(install_line(&load(&dir), "go", TAG).unwrap_err().message.contains("2 install.* files"));
    fs::remove_file(root.join("snippets/go/install.xml")).unwrap();
    fs::remove_file(root.join("snippets/go/install.sh")).unwrap();
    assert!(install_line(&load(&dir), "go", TAG).unwrap_err().message.contains("0 install.* files"));
}

// 1.10.26ag AC7
#[test]
fn install_line_follows_the_snippets_key() {
    let dir = fixture();
    let root = dir.path();
    let entry = |id: &str, registry: &str, extra: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"{registry}\"\npackage = \"p\"\n{extra}since = \"next\"\n")
    };
    let unity = entry("unity", "nuget", "snippets = \"snippets/player/unity\"\n");
    let text = fs::read_to_string(root.join("languages.toml")).unwrap();
    write(root, "languages.toml", &format!("{text}{unity}{}{}", entry("godot", "godot-assetlib", ""), entry("unreal", "fab", "")));
    write(root, "snippets/player/unity/install.sh", "dotnet add package Lingara.Embed --version {{version}}\n");
    write(root, "snippets/unity/install.sh", "the default directory, which the key replaces\n");
    let release = load(&dir);
    assert_eq!(install_line(&release, "unity", TAG).unwrap(), "dotnet add package Lingara.Embed --version 0.1.0-alpha.1\n");

    for store in ["godot", "unreal"] {
        let fail = install_line(&release, store, TAG).unwrap_err();
        assert_eq!(fail.code, 3, "{store}: {}", fail.message);
    }
}
