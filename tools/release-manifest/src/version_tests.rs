use std::fs;

use crate::manifest::check;
use crate::manifest_tests::{LANGUAGES, fixture, load, write};
use crate::version::{bump, check_tag, gem_version, parse};

// 29.9.26v AC2
#[test]
fn bump_writes_every_kind_and_check_agrees() {
    let dir = fixture();
    let root = dir.path();
    let go = "\n[[language]]\nid = \"go\"\nregistry = \"go\"\npackage = \"m\"\nsince = \"next\" # first release\n";
    write(root, "languages.toml", &format!("{}{go}", LANGUAGES.replace("\"0.1.0-alpha.1\"", "\"next\"")));
    write(root, "go/LICENSE", crate::manifest_tests::LICENCE);
    write(root, "snippets/go/install.sh", "go get m@v{{version}}\n");

    assert_eq!(bump(root, "0.2.0-beta.3").unwrap(), Vec::<String>::new());
    let read = |rel: &str| fs::read_to_string(root.join(rel)).unwrap();
    assert_eq!(read("VERSION"), "0.2.0-beta.3\n");
    let lock: serde_json::Value = serde_json::from_str(&read("typescript/package-lock.json")).unwrap();
    assert_eq!(lock["version"], "0.2.0-beta.3");
    assert_eq!(lock["packages"][""]["version"], "0.2.0-beta.3");
    assert!(read("typescript/package.json").starts_with("{\n  \"name\": \"@lingara/api\",\n  \"version\": \"0.2.0-beta.3\""));
    assert!(read("rust/Cargo.toml").contains("version = \"0.2.0-beta.3\" # lockstep"), "the comment survives");
    assert_eq!(read("languages.toml").matches("since = \"0.2.0-beta.3\"").count(), 3);
    assert!(read("languages.toml").contains("since = \"0.2.0-beta.3\" # first release"));
    assert!(check(&load(&dir)).is_empty());

    write(root, "rust/Cargo.toml", "[package]\nname = \"lingara\"\nversion = \"0.2.0\"\n");
    let findings = check(&load(&dir));
    assert_eq!(findings, vec!["rust/Cargo.toml: package.version reads \"0.2.0\", VERSION is \"0.2.0-beta.3\"".to_string()]);
}

// 29.9.26v AC3
#[test]
fn a_zero_major_needs_a_prerelease_suffix() {
    let dir = fixture();
    let root = dir.path();
    write(root, "VERSION", "0.2.0\n");
    assert!(check_tag(&load(&dir), "v0.2.0").iter().any(|f| f.contains("needs -alpha.N")));
    write(root, "VERSION", "0.2.0-alpha.1\n");
    assert_eq!(check_tag(&load(&dir), "v0.2.0-alpha.1"), Vec::<String>::new());
    assert!(check_tag(&load(&dir), "v0.2.0-alpha.2").iter().any(|f| f.contains("not v + VERSION")));
    assert!(check_tag(&load(&dir), "0.2.0-alpha.1").iter().any(|f| f.contains("not v + VERSION")));

    write(root, "languages.toml", &LANGUAGES.replacen("\"0.1.0-alpha.1\"", "\"next\"", 1));
    let findings = check_tag(&load(&dir), "v0.2.0-alpha.1");
    assert!(findings.iter().any(|f| f.contains("typescript still has since = \"next\"")), "{findings:?}");
}

#[test]
fn only_semver_parses() {
    assert_eq!(parse("1.2.3"), Some((1, None)));
    assert_eq!(parse("0.1.0-rc.2"), Some((0, Some("rc.2"))));
    for bad in ["1.2", "1.2.3.4", "v1.2.3", "1.2.3-", "1.2.3-a..b", "x.2.3"] {
        assert_eq!(parse(bad), None, "{bad}");
    }
}

// 29.9.26v AC4
#[test]
fn gem_version_is_the_rubygems_spelling() {
    assert_eq!(gem_version("0.1.0-alpha.1"), "0.1.0.pre.alpha.1");
    assert_eq!(gem_version("1.0.0"), "1.0.0");
}
