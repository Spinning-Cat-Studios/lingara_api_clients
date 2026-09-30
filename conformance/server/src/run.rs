//! `run --lang <lang> -- <harness…>` (ADR 29.9.26n D12, D13): serve every
//! case, run one harness against it, and trust neither side alone.

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};
use std::process::Command;

use serde::Deserialize;

use crate::case;
use crate::control::{LogEntry, Shared};
use crate::serve;

/// One line of the harness's results file (D13). Unknown keys are kept out
/// of the check on purpose: `duration_ms` and friends are informational.
#[derive(Debug, Clone, Deserialize)]
pub struct ResultLine {
    pub case: String,
    pub lang: String,
    pub result: String,
    #[serde(default)]
    pub client_mismatches: Vec<serde_json::Value>,
    #[serde(default)]
    pub server_mismatches: Vec<serde_json::Value>,
}

/// What `verify` compares: the case ids expected, and the server's record.
#[derive(Debug, Clone, Default)]
pub struct Record {
    pub expected: Vec<String>,
    pub log: Vec<LogEntry>,
    pub stray: Vec<String>,
}

/// D13: `LINGARA_CONFORMANCE_ONLY` is for local debugging; CI runs every case.
pub fn check_only(only: Option<&str>, ci: Option<&str>) -> Result<Option<BTreeSet<String>>, String> {
    let Some(only) = only.filter(|o| !o.trim().is_empty()) else { return Ok(None) };
    if ci.is_some_and(|c| !c.is_empty() && c != "false" && c != "0") {
        return Err("LINGARA_CONFORMANCE_ONLY is refused when CI is set: CI runs every case".into());
    }
    Ok(Some(only.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()))
}

/// D12 step 4: every problem with a results file, given what the server saw.
pub fn verify(lang: &str, lines: &[Result<ResultLine, String>], record: &Record) -> Vec<String> {
    let mut problems: Vec<String> = record.stray.iter().map(|s| format!("stray request: {s}")).collect();
    let armed: BTreeSet<&str> = log_ids(&record.log, true);
    let finished: BTreeSet<&str> = log_ids(&record.log, false);
    let mut seen: BTreeMap<&str, usize> = BTreeMap::new();
    for line in lines {
        let line = match line {
            Ok(line) => line,
            Err(e) => {
                problems.push(format!("results: {e}"));
                continue;
            }
        };
        *seen.entry(line.case.as_str()).or_default() += 1;
        problems.extend(line_problems(lang, line, &armed));
    }
    for (id, n) in &seen {
        if *n > 1 {
            problems.push(format!("{id}: reported {n} times"));
        }
    }
    for id in &record.expected {
        if !seen.contains_key(id.as_str()) {
            problems.push(format!("{id}: missing from the results"));
        }
    }
    for id in armed.difference(&finished) {
        problems.push(format!("{id}: armed but never finished"));
    }
    problems
}

fn log_ids(log: &[LogEntry], arms: bool) -> BTreeSet<&str> {
    log.iter()
        .filter_map(|e| match (e, arms) {
            (LogEntry::Arm(id), true) | (LogEntry::Finish(id), false) => Some(id.as_str()),
            _ => None,
        })
        .collect()
}

fn line_problems(lang: &str, line: &ResultLine, armed: &BTreeSet<&str>) -> Vec<String> {
    let mut problems = Vec::new();
    if line.lang != lang {
        problems.push(format!("{}: lang `{}` is not `{lang}`", line.case, line.lang));
    }
    if !armed.contains(line.case.as_str()) {
        problems.push(format!("{}: reported but never armed", line.case));
    }
    let clean = line.client_mismatches.is_empty() && line.server_mismatches.is_empty();
    if line.result != "pass" || !clean {
        let detail = serde_json::json!({ "client": line.client_mismatches, "server": line.server_mismatches });
        problems.push(format!("{}: {} {detail}", line.case, line.result));
    }
    problems
}

/// Parses the results file, one JSON object per non-blank line.
pub fn parse_results(text: &str) -> Vec<Result<ResultLine, String>> {
    let lines = text.lines().enumerate().filter(|(_, l)| !l.trim().is_empty());
    lines
        .map(|(n, l)| serde_json::from_str(l).map_err(|e| format!("line {}: {e}", n + 1)))
        .collect()
}

/// The `run` subcommand; returns the process exit code.
pub fn run(lang: &str, cases_dir: &Path, command: &[String]) -> i32 {
    match run_checked(lang, cases_dir, command) {
        Ok(problems) if problems.is_empty() => {
            println!("conformance {lang}: every case passed");
            0
        }
        Ok(problems) => {
            problems.iter().for_each(|p| eprintln!("✗ {p}"));
            eprintln!("conformance {lang}: {} problem(s)", problems.len());
            1
        }
        Err(e) => {
            eprintln!("✗ conformance {lang}: {e}");
            1
        }
    }
}

fn run_checked(lang: &str, cases_dir: &Path, command: &[String]) -> Result<Vec<String>, String> {
    let Some((program, args)) = command.split_first() else {
        return Err(format!("no harness command: set CONFORMANCE_CMD_{lang}"));
    };
    let only_env = std::env::var("LINGARA_CONFORMANCE_ONLY").ok();
    let only = check_only(only_env.as_deref(), std::env::var("CI").ok().as_deref())?;
    let (cases, errors) = case::load_dir(cases_dir);
    if !errors.is_empty() {
        return Err(errors.join("\n"));
    }
    let mut expected: Vec<String> = cases.iter().map(|c| c.case.id.clone()).collect();
    if let Some(only) = &only {
        expected.retain(|id| only.contains(id));
    }
    let shared = Shared::new(cases);
    let runtime = tokio::runtime::Runtime::new().map_err(|e| e.to_string())?;
    let (port, _server) = runtime.block_on(serve::start(shared.clone(), 0)).map_err(|e| e.to_string())?;
    let out = results_path(lang);
    let _ = std::fs::remove_file(&out);
    let status = Command::new(program)
        .args(args)
        .envs(harness_env(port, &out))
        .status()
        .map_err(|e| format!("cannot start `{program}`: {e}"))?;
    let text = std::fs::read_to_string(&out).unwrap_or_default();
    let state = shared.lock();
    let record = Record { expected, log: state.log.clone(), stray: state.stray.clone() };
    let mut problems = verify(lang, &parse_results(&text), &record);
    if !status.success() {
        problems.push(format!("the harness exited with {status}"));
    }
    Ok(problems)
}

fn results_path(lang: &str) -> PathBuf {
    std::env::temp_dir().join(format!("lingara-conformance-{lang}-{}.jsonl", std::process::id()))
}

/// D13's environment.
pub fn harness_env(port: u16, out: &Path) -> Vec<(String, String)> {
    let base = format!("http://127.0.0.1:{port}");
    vec![
        ("LINGARA_CONFORMANCE_BASE_URL".into(), base.clone()),
        ("LINGARA_CONFORMANCE_TOKEN_URL".into(), format!("{base}/oauth/token")),
        ("LINGARA_CONFORMANCE_CONTROL_URL".into(), format!("{base}/__conformance")),
        ("LINGARA_CONFORMANCE_OUT".into(), out.display().to_string()),
    ]
}
