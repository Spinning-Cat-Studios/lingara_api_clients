//! `release-manifest` (ADR 29.9.26v D2): the checks a reviewer would otherwise
//! do by eye over `languages.toml`, and the names, URLs and lines a release is
//! built from.
//!
//! It reads files and git only. It holds no credential and makes no network
//! call: `probe` prints a URL, and the workflow fetches it.
//!
//! Exit codes: 0 holds, 1 findings, 2 input that could not be read (or a
//! publish check run where `publish/` does not exist), 3 `probe` on a registry
//! with no per-version URL.

pub mod changelog;
pub mod install;
pub mod manifest;
pub mod matrix;
pub mod publish;
pub mod registry;
pub mod version;

#[cfg(test)]
mod changelog_tests;
#[cfg(test)]
mod install_tests;
#[cfg(test)]
mod manifest_tests;
#[cfg(test)]
mod matrix_tests;
#[cfg(test)]
mod registry_tests;
#[cfg(test)]
mod version_tests;

/// Why a subcommand did not print its answer, and the exit code that says so.
#[derive(Debug, PartialEq, Eq)]
pub struct Fail {
    pub code: i32,
    pub message: String,
}

impl Fail {
    /// Exit 2: an input that could not be read, or a question with no answer.
    pub fn input(message: impl Into<String>) -> Self {
        Self { code: 2, message: message.into() }
    }

    /// Exit 1, one `✗` line per finding.
    pub fn findings(findings: &[String]) -> Self {
        let lines: Vec<String> = findings.iter().map(|f| format!("✗ {f}")).collect();
        Self { code: 1, message: lines.join("\n") }
    }
}

/// `Ok` when there is nothing to report, otherwise every finding at once.
pub fn verdict(findings: Vec<String>, holds: &str) -> Result<String, Fail> {
    if findings.is_empty() { Ok(format!("✓ {holds}\n")) } else { Err(Fail::findings(&findings)) }
}
