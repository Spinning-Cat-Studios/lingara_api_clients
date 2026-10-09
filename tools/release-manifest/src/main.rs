//! `release-manifest <subcommand>` (ADR 29.9.26v D2), run from the repository
//! root. Tree checks run anywhere; the two publish checks need `publish/`,
//! which exists in private and staging only.

use std::path::Path;

use release_manifest::manifest::{self, Release};
use release_manifest::{Fail, changelog, current, install, matrix, publish, registry, verdict, version};

const USAGE: &str = "usage: release-manifest <subcommand>
  tree checks:    check | check-tag <tag> | bump <version> | matrix <tag> | langs
                  artefact <id> <tag> | install-line <id> <tag> | spec-line
                  check-current [--registry <path>] <versions.json>
                  gem-version <semver> | probe <id> <tag>
  publish checks: check-publish | check-changelog";

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match dispatch(&args, Path::new(".")) {
        Ok(out) => {
            print!("{out}");
            0
        }
        Err(fail) => {
            if !fail.message.is_empty() {
                eprintln!("{}", fail.message);
            }
            fail.code
        }
    };
    std::process::exit(code);
}

fn line(text: String) -> String {
    format!("{text}\n")
}

/// The subcommands that need no `languages.toml`, then the rest.
fn dispatch(args: &[String], root: &Path) -> Result<String, Fail> {
    let words: Vec<&str> = args.iter().map(String::as_str).collect();
    match words.as_slice() {
        ["gem-version", semver] => Ok(line(version::gem_version(semver))),
        ["spec-line"] => changelog::spec_line(root).map(line),
        ["check-current", rest @ ..] => current::run(root, rest),
        ["bump", to] => verdict(version::bump(root, to)?, &format!("bumped to {to}; languages.toml agrees")),
        _ => with_release(&Release::load(root)?, &words),
    }
}

fn with_release(release: &Release, words: &[&str]) -> Result<String, Fail> {
    match words {
        ["check"] => verdict(manifest::check(release), "languages.toml agrees with the tree"),
        ["check-tag", tag] => verdict(version::check_tag(release, tag), &format!("{tag} may be released")),
        ["matrix", tag] => Ok(matrix::matrix(release, tag)),
        ["langs"] => Ok(line(install::langs(release))),
        ["artefact", id, tag] => Ok(install::artefact(release, id, tag)?.map(|(b, u)| line(format!("{b}\t{u}"))).unwrap_or_default()),
        ["install-line", id, tag] => install::install_line(release, id, tag),
        // Packagist and the stores have no per-version URL: nothing printed, exit 3.
        ["probe", id, tag] => match registry::probe(release, id, tag)? {
            Some(urls) => Ok(urls.iter().map(|u| line(u.clone())).collect()),
            None => Err(Fail { code: 3, message: String::new() }),
        },
        ["check-publish"] => verdict(publish::check_publish(release)?, "languages.toml, the allowlist and snapshot.toml agree"),
        ["check-changelog"] => verdict(changelog::check_changelog(release)?, "changelog/unreleased.md names its spec and every new language"),
        _ => Err(Fail::input(USAGE)),
    }
}
