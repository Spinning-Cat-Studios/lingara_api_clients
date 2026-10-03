//! How an observed call is compared with a case's `expect` (conformance/
//! README.md, Comparison rules). Pure: no I/O, no library import.

use serde_json::{Map, Value};

/// One call as the harness saw it, in the contract's vocabulary.
#[derive(Debug, Default)]
pub struct Observed {
    pub outcome: &'static str,
    pub status: Option<u16>,
    pub body: Option<Value>,
    pub events: Vec<Value>,
    /// The variant name and the contract's snake_case fields.
    pub error: Option<(String, Map<String, Value>)>,
    pub served_version: Option<String>,
    pub sleeps_s: Vec<u64>,
    pub hook_calls: Vec<Value>,
    /// Every rendering of the client and of a raised error.
    pub renderings: Vec<String>,
    /// An `events` or `tail` step's yielded ids, the types of those that
    /// were `UnknownEvent`, and the helper's final cursor (ADR 30.9.26aa D9).
    pub event_ids: Vec<String>,
    pub unknown_types: Vec<String>,
    pub cursor: Option<String>,
}

/// JSON with `null`-valued keys dropped and object keys sorted.
pub fn canon(value: &Value) -> String {
    normalise(value).to_string()
}

fn normalise(value: &Value) -> Value {
    match value {
        Value::Array(items) => Value::Array(items.iter().map(normalise).collect()),
        Value::Object(map) => {
            let mut entries: Vec<(&String, &Value)> = map.iter().filter(|(_, v)| !v.is_null()).collect();
            entries.sort_by(|a, b| a.0.cmp(b.0));
            Value::Object(entries.into_iter().map(|(k, v)| (k.clone(), normalise(v))).collect())
        }
        other => other.clone(),
    }
}

/// Replaces `{base_url}` in every string of an expected value.
pub fn substitute(value: &Value, base_url: &str) -> Value {
    match value {
        Value::String(s) => Value::String(s.replace("{base_url}", base_url)),
        Value::Array(items) => Value::Array(items.iter().map(|v| substitute(v, base_url)).collect()),
        Value::Object(map) => Value::Object(map.iter().map(|(k, v)| (k.clone(), substitute(v, base_url))).collect()),
        other => other.clone(),
    }
}

fn same(label: &str, expected: &Value, actual: &Value, out: &mut Vec<String>) {
    let (want, got) = (canon(expected), canon(actual));
    if want != got {
        out.push(format!("{label}: expected {want}, got {got}"));
    }
}

/// Every difference between one observed call and its expectation.
pub fn compare(expect: &Value, seen: &Observed) -> Vec<String> {
    let mut out = Vec::new();
    let want_outcome = expect.get("outcome").and_then(Value::as_str).unwrap_or_default();
    if seen.outcome != want_outcome {
        let detail = seen.error.as_ref().map(|(v, f)| format!(" ({v} {})", canon(&Value::Object(f.clone())))).unwrap_or_default();
        out.push(format!("outcome: expected {want_outcome}, got {}{detail}", seen.outcome));
    }
    let status = seen.status.or_else(|| seen.error.as_ref().and_then(|(_, f)| f.get("status")?.as_u64()).map(|s| s as u16));
    let pairs = [
        ("status", status.map(Value::from).unwrap_or(Value::Null)),
        ("body", seen.body.clone().unwrap_or(Value::Null)),
        ("events", Value::Array(seen.events.clone())),
        ("served_version", seen.served_version.clone().map(Value::String).unwrap_or(Value::Null)),
        ("sleeps_s", Value::from(seen.sleeps_s.clone())),
        ("hook_calls", Value::Array(seen.hook_calls.clone())),
        ("event_ids", Value::from(seen.event_ids.clone())),
        ("unknown_types", Value::from(seen.unknown_types.clone())),
    ];
    for (label, got) in pairs {
        if let Some(want) = expect.get(label) {
            same(label, want, &got, &mut out);
        }
    }
    if let Some(error) = expect.get("error") {
        compare_error(error, seen, &mut out);
    }
    if let Some(matcher) = expect.get("cursor") {
        compare_cursor(matcher, seen.cursor.as_deref(), &mut out);
    }
    let secrets = expect.get("redacted").and_then(Value::as_array).cloned().unwrap_or_default();
    for secret in secrets.iter().filter_map(Value::as_str) {
        if seen.renderings.iter().any(|r| r.contains(secret)) {
            out.push(format!("redacted: a rendering contains {}…", &secret[..secret.len().min(12)]));
        }
    }
    out
}

/// `expect.cursor` is a header-style matcher on the helper's final cursor.
fn compare_cursor(matcher: &Value, cursor: Option<&str>, out: &mut Vec<String>) {
    let want = |key: &str| matcher.get(key).and_then(Value::as_str);
    let pass = match (cursor, want("equals"), want("prefix"), want("contains")) {
        (got, None, None, None) if matcher.get("absent") == Some(&Value::Bool(true)) => got.is_none(),
        (Some(got), Some(equals), _, _) => got == equals,
        (Some(got), _, Some(prefix), _) => got.starts_with(prefix),
        (Some(got), _, _, Some(part)) => got.contains(part),
        _ => false,
    };
    if !pass {
        out.push(format!("cursor: expected {matcher}, got {cursor:?}"));
    }
}

fn compare_error(expected: &Value, seen: &Observed, out: &mut Vec<String>) {
    let want_variant = expected.get("variant").and_then(Value::as_str).unwrap_or_default();
    let Some((variant, fields)) = &seen.error else {
        out.push(format!("error: expected {want_variant}, got none"));
        return;
    };
    if variant != want_variant {
        out.push(format!("error.variant: expected {want_variant}, got {variant}"));
    }
    let wanted = expected.get("fields").and_then(Value::as_object).cloned().unwrap_or_default();
    for (field, want) in &wanted {
        same(&format!("error.{field}"), want, fields.get(field).unwrap_or(&Value::Null), out);
    }
}
