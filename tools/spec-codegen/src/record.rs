//! One `x-lingara-streams` entry per lifted operation (ADR 29.9.26m D4).
//!
//! The hand-written cores read this list to prove they wrap every stream the
//! spec has, and the conformance coverage guard reads each entry's events.
//! Since ADR 29.9.26ai D1 the entry also carries how the stream ends, copied
//! from the Backend's `x-lingara-stream` and validated here, once.

use serde_json::{json, Map, Value};

use crate::lift::{events, union_name, Stream};
use crate::Refusal;

/// The keys `x-lingara-stream` may carry. A new server fact is a new ADR in
/// this repo, never a key the view drops (29.9.26ai Consequences).
const EXTENSION_KEYS: [&str; 4] = ["ends_on", "error", "keepalive_seconds", "resumable"];

/// The one payload D2's rule ends on unyielded; it must stay able to carry
/// nothing.
const DONE_REF: &str = "#/components/schemas/Done";

/// The `x-lingara-streams` entry: everything a core needs to call the
/// operation that `paths` no longer carries, and how its stream ends.
pub(crate) fn record(doc: &Value, s: &Stream) -> Result<Value, Refusal> {
    let op_id = s.op_id.as_str();
    let item = &doc["paths"][&s.path];
    let op = &item[&s.method];
    let mut parameters = Vec::new();
    for p in item_params(item).chain(item_params(op)) {
        let r = p.get("$ref").and_then(Value::as_str).ok_or_else(|| {
            Refusal(format!("operation {op_id}: a parameter is inline, not a $ref"))
        })?;
        parameters.push(json!(r));
    }
    let ending = Ending::read(s)?;
    check_done(doc, s, &ending)?;
    Ok(json!({
        "operationId": op_id,
        "method": s.method,
        "path": s.path,
        "requestBody": request_body_ref(op, op_id)?,
        "parameters": parameters,
        "scopes": scopes(doc, op),
        "union": union_name(op_id),
        "events": events(s),
        "endsOn": ending.ends_on,
        "error": ending.error,
        "keepaliveSeconds": ending.keepalive_seconds,
        "resumable": ending.resumable,
    }))
}

/// A validated `x-lingara-stream`.
struct Ending {
    ends_on: Vec<String>,
    error: String,
    keepalive_seconds: Option<u64>,
    resumable: bool,
}

impl Ending {
    fn read(s: &Stream) -> Result<Self, Refusal> {
        let op_id = &s.op_id;
        let bad = |what: &str| Refusal(format!("operation {op_id}: x-lingara-stream {what}"));
        let ext = s
            .extension
            .as_ref()
            .ok_or_else(|| Refusal(format!("operation {op_id}: the stream has no x-lingara-stream")))?
            .as_object()
            .ok_or_else(|| bad("is not an object"))?;
        if let Some(k) = ext.keys().find(|k| !EXTENSION_KEYS.contains(&k.as_str())) {
            return Err(bad(&format!("has an unknown key {k:?}")));
        }
        let ends_on = string_list(ext, "ends_on").ok_or_else(|| bad("ends_on is not a list of event names"))?;
        if ends_on.is_empty() {
            return Err(bad("ends_on is empty"));
        }
        let events = events(s);
        if let Some(e) = ends_on.iter().find(|e| !events.contains(&e.as_str())) {
            return Err(bad(&format!("ends_on names {e:?}, which is not one of the operation's events")));
        }
        let error = ext.get("error").and_then(Value::as_str).ok_or_else(|| bad("error is not an event name"))?;
        if !ends_on.iter().any(|e| e == error) {
            return Err(bad(&format!("error {error:?} is not in ends_on")));
        }
        let keepalive_seconds = match ext.get("keepalive_seconds") {
            None => None,
            Some(v) => Some(v.as_u64().filter(|n| *n > 0).ok_or_else(|| bad("keepalive_seconds is not a positive integer"))?),
        };
        let resumable = ext.get("resumable").and_then(Value::as_bool).ok_or_else(|| bad("resumable is not a boolean"))?;
        Ok(Self { ends_on, error: error.to_owned(), keepalive_seconds, resumable })
    }
}

fn string_list(ext: &Map<String, Value>, key: &str) -> Option<Vec<String>> {
    ext.get(key)?.as_array()?.iter().map(|v| v.as_str().map(str::to_owned)).collect()
}

/// D2 ends a stream unyielded on a `Done` payload, which is only safe while
/// `Done` can carry nothing: so when a non-error ending event is `Done`, the
/// schema must be exactly `{type: object}`.
fn check_done(doc: &Value, s: &Stream, ending: &Ending) -> Result<(), Refusal> {
    let ends_on_done = s.branches.iter().any(|b| {
        b.data_ref == DONE_REF && b.event != ending.error && ending.ends_on.contains(&b.event)
    });
    if ends_on_done && doc["components"]["schemas"]["Done"] != json!({ "type": "object" }) {
        return Err(Refusal(format!(
            "operation {}: an ending event's payload is Done, which is not exactly {{type: object}}",
            s.op_id
        )));
    }
    Ok(())
}

fn item_params(v: &Value) -> impl Iterator<Item = &Value> {
    v.get("parameters").and_then(Value::as_array).into_iter().flatten()
}

/// The request body's `$ref`: the body's own, else its JSON schema's.
fn request_body_ref(op: &Value, op_id: &str) -> Result<Value, Refusal> {
    let Some(body) = op.get("requestBody") else { return Ok(Value::Null) };
    body.get("$ref")
        .or_else(|| body.pointer("/content/application~1json/schema/$ref"))
        .cloned()
        .ok_or_else(|| Refusal(format!("operation {op_id}: the request body is not a $ref")))
}

/// Every scope the operation's security requirements name (the document's
/// when the operation has none), in order, each once.
fn scopes(doc: &Value, op: &Value) -> Vec<Value> {
    let reqs = op.get("security").or_else(|| doc.get("security"));
    let mut out: Vec<Value> = Vec::new();
    let all = reqs.and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_object);
    for scope in all.flat_map(|r| r.values()).filter_map(Value::as_array).flatten() {
        if !out.contains(scope) {
            out.push(scope.clone());
        }
    }
    out
}
