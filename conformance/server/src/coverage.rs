//! `check-coverage` (ADR 29.9.26n D14): every operation has a case, every
//! case names a real operation, every behaviour is exercised, every case
//! parses, and the view still carries each stream's terminal events.

use std::collections::BTreeSet;
use std::path::Path;

use serde_json::Value;

use crate::case::{self, BEHAVIOURS, Loaded};

/// D6's idle timeout (120 s) is eight missed keepalives of this interval;
/// a longer one re-opens that number (ADR 29.9.26ai D3).
pub const MAX_KEEPALIVE_SECONDS: u64 = 15;

const METHODS: &[&str] = &["get", "put", "post", "delete", "options", "head", "patch", "trace", "query"];

/// Every `operationId` in the spec's `paths`.
pub fn spec_operations(spec: &Value) -> BTreeSet<String> {
    let paths = spec.get("paths").and_then(Value::as_object).into_iter().flatten();
    let ops = paths.flat_map(|(_, item)| METHODS.iter().filter_map(move |m| item.get(*m)));
    ops.filter_map(|op| op.get("operationId")?.as_str().map(str::to_string)).collect()
}

/// Rules 1, 2, 3 and 5 over parsed cases; rule 4 is `case::load_dir`'s.
pub fn check(spec: &Value, view: &Value, cases: &[Loaded]) -> Vec<String> {
    let spec_ops = spec_operations(spec);
    let mut problems = Vec::new();
    let mut called = BTreeSet::new();
    for loaded in cases {
        for call in loaded.case.steps.iter().filter_map(|s| s.call.as_ref()) {
            if !spec_ops.contains(&call.operation) {
                problems.push(format!("{}: `{}` is not an operation in the spec", loaded.case.id, call.operation));
            }
            called.insert(call.operation.clone());
        }
    }
    for op in spec_ops.difference(&called) {
        problems.push(format!("operation `{op}` has no case"));
    }
    for behaviour in BEHAVIOURS {
        if !cases.iter().any(|c| c.case.behaviours.contains(&behaviour)) {
            problems.push(format!("{behaviour:?} has no case listing it in `behaviours`"));
        }
    }
    problems.extend(terminal_problems(view));
    problems
}

/// Rule 5 over each `x-lingara-streams` entry of the view (ADR 29.9.26ai
/// D3): every `endsOn` event is one of its `events`, no keepalive is longer
/// than 15 s, and a resumable entry names its `error` in `endsOn` (the K5a
/// tail, ADR 30.9.26aa D7; the `Done`-payload half is `spec-codegen`'s). It
/// reads the view and trusts no producer, so it overlaps `spec-codegen`'s
/// own refusal on purpose.
pub fn terminal_problems(view: &Value) -> Vec<String> {
    let streams = view.get("x-lingara-streams").and_then(Value::as_array).cloned().unwrap_or_default();
    streams.iter().flat_map(entry_problems).collect()
}

fn entry_problems(entry: &Value) -> Vec<String> {
    let op = entry.get("operationId").and_then(Value::as_str).unwrap_or("?");
    let list = |key| entry.get(key).and_then(Value::as_array).cloned().unwrap_or_default();
    let (events, ends_on) = (list("events"), list("endsOn"));
    let mut problems = Vec::new();
    if ends_on.is_empty() {
        problems.push(format!("`{op}`: the view names no endsOn"));
    }
    for terminal in ends_on.iter().filter(|t| !events.contains(t)) {
        let terminal = terminal.as_str().unwrap_or("?");
        problems.push(format!("`{op}`: terminal event `{terminal}` is not in the view's events"));
    }
    if let Some(secs) = entry.get("keepaliveSeconds").and_then(Value::as_u64).filter(|s| *s > MAX_KEEPALIVE_SECONDS) {
        problems.push(format!(
            "`{op}`: keepalive every {secs} s exceeds {MAX_KEEPALIVE_SECONDS} s, so D6's 120 s idle timeout is no longer eight missed keepalives; re-decide it in C2 (29.9.26n D6)"
        ));
    }
    let error = entry.get("error").filter(|e| e.is_string());
    if entry.get("resumable") == Some(&Value::Bool(true)) && !error.is_some_and(|e| ends_on.contains(e)) {
        problems.push(format!(
            "`{op}`: the stream is resumable but does not end on its error event, so it is not a K5a tail; any other resumable stream re-opens C2's decision to send no Last-Event-ID (29.9.26n, What We Explicitly Avoid; ADR 30.9.26aa D7)"
        ));
    }
    problems
}

/// The `check-coverage` subcommand; returns the process exit code.
pub fn run(spec: &Path, view: &Path, cases_dir: &Path) -> i32 {
    let (cases, mut problems) = case::load_dir(cases_dir);
    match (read_json(spec), read_json(view)) {
        (Ok(spec), Ok(view)) => problems.extend(check(&spec, &view, &cases)),
        (spec, view) => problems.extend([spec.err(), view.err()].into_iter().flatten()),
    }
    if problems.is_empty() {
        println!("conformance coverage: {} cases cover every operation, K1–K6 and K5a", cases.len());
        return 0;
    }
    problems.iter().for_each(|p| eprintln!("✗ {p}"));
    1
}

fn read_json(path: &Path) -> Result<Value, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))
}
