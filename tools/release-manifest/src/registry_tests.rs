use crate::manifest_tests::{fixture, load, write};
use crate::registry::{case_encode, probe};

const TAG: &str = "v0.1.0-alpha.1";

// 29.9.26v AC8
#[test]
fn each_probe_names_its_package_and_version() {
    let dir = fixture();
    let entry = |id: &str, registry: &str, package: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"{registry}\"\npackage = \"{package}\"\nsince = \"next\"\n")
    };
    let extra = [
        entry("go", "go", "github.com/Spinning-Cat-Studios/lingara_api_clients/go"),
        entry("java", "maven-central", "com.getlingara:lingara-java"),
        entry("kotlin", "maven-central", "com.getlingara:lingara-kotlin"),
        entry("ruby", "rubygems", "lingara"),
        entry("php", "packagist", "spinningcatstudios/lingara"),
    ];
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("{text}{}", extra.concat()));
    let release = load(&dir);
    let url = |id: &str| probe(&release, id, TAG).unwrap().unwrap();

    assert_eq!(url("typescript"), ["https://registry.npmjs.org/@lingara/api/0.1.0-alpha.1"]);
    assert_eq!(url("rust"), ["https://crates.io/api/v1/crates/lingara/0.1.0-alpha.1"]);
    assert_eq!(url("ruby"), ["https://rubygems.org/api/v2/rubygems/lingara/versions/0.1.0.pre.alpha.1.json"]);
    assert_eq!(
        url("java"),
        ["https://repo1.maven.org/maven2/com/getlingara/lingara-java/0.1.0-alpha.1/lingara-java-0.1.0-alpha.1.pom"]
    );
    assert_eq!(
        url("kotlin"),
        ["https://repo1.maven.org/maven2/com/getlingara/lingara-kotlin/0.1.0-alpha.1/lingara-kotlin-0.1.0-alpha.1.pom"]
    );
    assert_eq!(
        url("go"),
        ["https://proxy.golang.org/github.com/!spinning-!cat-!studios/lingara_api_clients/go/@v/v0.1.0-alpha.1.info"]
    );
    assert_eq!(probe(&release, "php", TAG), Ok(None), "packagist has no per-version URL: main exits 3");
}

#[test]
fn case_encoding_marks_each_capital() {
    assert_eq!(case_encode("github.com/Azure/SDK"), "github.com/!azure/!s!d!k");
}

// 1.10.26ag AC5
#[test]
fn nuget_probe_is_the_lowercased_nuspec() {
    let dir = fixture();
    let entry = |id: &str, registry: &str, package: &str| {
        format!("\n[[language]]\nid = \"{id}\"\nregistry = \"{registry}\"\npackage = \"{package}\"\nsince = \"next\"\n")
    };
    let extra = [entry("unity", "nuget", "Lingara.Embed"), entry("godot", "godot-assetlib", "Lingara"), entry("unreal", "fab", "Lingara")];
    let text = std::fs::read_to_string(dir.path().join("languages.toml")).unwrap();
    write(dir.path(), "languages.toml", &format!("{text}{}", extra.concat()));
    let release = load(&dir);

    assert_eq!(
        probe(&release, "unity", "v0.1.0-RC.1").unwrap().unwrap(),
        ["https://api.nuget.org/v3-flatcontainer/lingara.embed/0.1.0-rc.1/lingara.embed.nuspec"]
    );
    assert_eq!(probe(&release, "godot", TAG), Ok(None), "a store has no per-version URL: main exits 3");
    assert_eq!(probe(&release, "unreal", TAG), Ok(None));
}
