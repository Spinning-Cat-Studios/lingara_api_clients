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

const RB: &str = "module Lingara\n  module Cli\n    VERSION = \"0.1.0-alpha.1\" # lockstep\n  end\nend\n";
const PHP: &str = "<?php\r\n\r\nfinal class Version\r\n{\r\n    public const VERSION = '0.1.0-alpha.1';\r\n}\r\n";

/// A `ruby` entry whose two `line` version files are written as `rb` and
/// `php`, keyed by `rb_key` and `php_key`; at `fixture()`'s VERSION `check` passes.
fn line_files(root: &std::path::Path, (rb_key, rb): (&str, &str), (php_key, php): (&str, &str)) {
    let entry = format!(
        "\n[[language]]\nid = \"ruby\"\nregistry = \"rubygems\"\npackage = \"lingara-cli\"\nversion_files = [\n  \
         {{ path = \"ruby/version.rb\", kind = \"line\", keys = [{rb_key:?}] }},\n  \
         {{ path = \"ruby/Version.php\", kind = \"line\", keys = [{php_key:?}] }},\n]\nsince = \"next\"\n"
    );
    write(root, "languages.toml", &format!("{LANGUAGES}{entry}"));
    write(root, "ruby/LICENSE", crate::manifest_tests::LICENCE);
    write(root, "snippets/ruby/install.sh", "gem install lingara-cli -v {{version}}\n");
    write(root, "ruby/version.rb", rb);
    write(root, "ruby/Version.php", php);
}

const RB_KEY: &str = "VERSION = \"";
const PHP_KEY: &str = "public const VERSION = '";

// 22.10.26r AC6
#[test]
fn bump_rewrites_only_a_line_files_value() {
    let dir = fixture();
    let root = dir.path();
    line_files(root, (RB_KEY, RB), (PHP_KEY, PHP));
    assert_eq!(check(&load(&dir)), Vec::<String>::new());

    assert_eq!(bump(root, "0.2.0-beta.3").unwrap(), Vec::<String>::new());
    let read = |rel: &str| fs::read_to_string(root.join(rel)).unwrap();
    assert_eq!(read("ruby/version.rb"), RB.replace("0.1.0-alpha.1", "0.2.0-beta.3"));
    assert_eq!(read("ruby/Version.php"), PHP.replace("0.1.0-alpha.1", "0.2.0-beta.3"), "CRLF and indentation kept");
}

// 22.10.26r AC7
#[test]
fn check_refuses_each_bad_line_key() {
    let dir = fixture();
    let root = dir.path();
    let cases = [
        ("VERSION = ", RB, "ruby/version.rb: line prefix `VERSION = ` does not end in \" or '"),
        ("GEM_VERSION = \"", RB, "ruby/version.rb: line prefix `GEM_VERSION = \"` matches no line"),
        (RB_KEY, "VERSION = \"0.1.0-alpha.1\"\n  VERSION = \"x\"\n", "ruby/version.rb: line prefix `VERSION = \"` matches more than one line"),
        (RB_KEY, "  VERSION = \"0.1.0-alpha.1\n", "ruby/version.rb: line prefix `VERSION = \"` has no closing quote on its line"),
        (RB_KEY, "  VERSION = \"0.0.0\"\n", "ruby/version.rb: VERSION = \" reads \"0.0.0\", VERSION is \"0.1.0-alpha.1\""),
    ];
    for (key, rb, finding) in cases {
        line_files(root, (key, rb), (PHP_KEY, PHP));
        assert_eq!(check(&load(&dir)), vec![finding.to_string()], "{key:?} over {rb:?}");
    }
}

// 22.10.26r AC14
#[test]
fn bump_refuses_a_bad_line_key_and_leaves_the_file() {
    let dir = fixture();
    let root = dir.path();
    let cases = [
        ("public const VERSION = ", PHP),
        ("const LATER = '", PHP),
        (PHP_KEY, "    public const VERSION = '1';\n    public const VERSION = '2';\n"),
        (PHP_KEY, "    public const VERSION = '0.1.0-alpha.1;\n"),
    ];
    for (key, php) in cases {
        line_files(root, (RB_KEY, RB), (key, php));
        let fail = bump(root, "0.2.0").unwrap_err();
        assert_eq!(fail.code, 2, "{key:?}: {}", fail.message);
        assert!(fail.message.starts_with("ruby/Version.php: line prefix"), "{}", fail.message);
        assert_eq!(fs::read_to_string(root.join("ruby/Version.php")).unwrap(), php, "{key:?}");
    }
}

// 1.10.26ag AC8
#[test]
fn a_prefixed_version_file_carries_its_prefix() {
    let dir = fixture();
    let root = dir.path();
    let bevy = r#"
[[language]]
id = "bevy"
registry = "crates.io"
package = "bevy_lingara"
version_files = [
  { path = "bevy/Cargo.toml", kind = "toml", keys = ["package.version"] },
  { path = "bevy/Cargo.toml", kind = "toml", keys = ["dependencies.lingara-embed.version"], prefix = "=" },
]
since = "next"
"#;
    write(root, "languages.toml", &format!("{LANGUAGES}{bevy}"));
    let cargo = "[package]\nname = \"bevy_lingara\"\nversion = \"0.0.0\"\n\n[dependencies]\nlingara-embed = { version = \"=0.0.0\", path = \"../core\" }\n";
    write(root, "bevy/Cargo.toml", cargo);
    write(root, "bevy/LICENSE", crate::manifest_tests::LICENCE);
    write(root, "snippets/bevy/install.sh", "cargo add bevy_lingara@{{version}}\n");

    assert_eq!(bump(root, "0.1.0-alpha.2").unwrap(), Vec::<String>::new());
    let written = fs::read_to_string(root.join("bevy/Cargo.toml")).unwrap();
    assert!(written.contains("version = \"0.1.0-alpha.2\"\n"), "{written}");
    assert!(written.contains("lingara-embed = { version = \"=0.1.0-alpha.2\", path = \"../core\" }"), "{written}");

    // `check` refuses the pin without its prefix.
    write(root, "bevy/Cargo.toml", &written.replace("\"=0.1.0-alpha.2\"", "\"0.1.0-alpha.2\""));
    assert_eq!(
        check(&load(&dir)),
        vec![
            "bevy/Cargo.toml: dependencies.lingara-embed.version reads \"0.1.0-alpha.2\", expected \"=0.1.0-alpha.2\" (prefix \"=\" + VERSION)"
                .to_string()
        ]
    );
}
