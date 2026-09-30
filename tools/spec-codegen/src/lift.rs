//! Lift each streamed operation out of `paths` (ADR 29.9.26m D4).
//!
//! No mainstream generator reads a 3.2 `itemSchema`, so every
//! `text/event-stream` response's event branches become named component
//! schemas under one discriminated union per operation, and the operation
//! itself moves from `paths` to the top-level `x-lingara-streams` list the
//! hand-written cores read. A branch in any other shape is refused: the lift
//! never guesses what an unfamiliar branch means.

use serde_json::{json, Map, Value};

use crate::record;
use crate::walk::{escape, METHODS};
use crate::Refusal;

const SCHEMA_PREFIX: &str = "#/components/schemas/";
const STREAM_MEDIA: &str = "text/event-stream";

/// One event branch: `event: <name>`, whose `data` decodes as `data_ref`.
pub(crate) struct Branch {
    pub(crate) event: String,
    pub(crate) data_ref: String,
}

/// One streamed operation, as found in `paths`, with its media object's
/// `x-lingara-stream` — `None` when it has none (ADR 29.9.26ai D1).
pub(crate) struct Stream {
    pub(crate) path: String,
    pub(crate) method: String,
    pub(crate) op_id: String,
    pub(crate) branches: Vec<Branch>,
    pub(crate) extension: Option<Value>,
}

/// Lift every stream in `doc`, in document order. On a refusal `doc` may be
/// half-lifted; the caller discards it.
pub fn lift(doc: &mut Value) -> Result<(), Refusal> {
    let streams = find_streams(doc)?;
    let mut records = Vec::new();
    for s in &streams {
        insert_schemas(doc, named_schemas(s)?)?;
        records.push(record::record(doc, s)?);
    }
    for s in &streams {
        strip(doc, s);
    }
    if let Some(root) = doc.as_object_mut() {
        root.insert("x-lingara-streams".into(), Value::Array(records));
    }
    Ok(())
}

fn find_streams(doc: &Value) -> Result<Vec<Stream>, Refusal> {
    let mut out = Vec::new();
    let Some(paths) = doc.get("paths").and_then(Value::as_object) else {
        return Ok(out);
    };
    for (path, item) in paths {
        for method in METHODS {
            let Some(op) = item.get(method) else { continue };
            let ptr = format!("/paths/{}/{method}", escape(path));
            if let Some(media) = stream_media(op, &ptr)? {
                let op_id = op_id(op, &ptr)?;
                let branches = parse_branches(&media["itemSchema"], &op_id)?;
                let extension = media.get("x-lingara-stream").cloned();
                out.push(Stream { path: path.clone(), method: method.into(), op_id, branches, extension });
            }
        }
    }
    Ok(out)
}

/// The operation's one `text/event-stream` media object with an
/// `itemSchema`, if it has one.
fn stream_media<'a>(op: &'a Value, ptr: &str) -> Result<Option<&'a Value>, Refusal> {
    let Some(responses) = op.get("responses").and_then(Value::as_object) else {
        return Ok(None);
    };
    let found: Vec<&Value> = responses
        .values()
        .filter_map(|r| r.pointer(&format!("/content/{}", escape(STREAM_MEDIA))))
        .filter(|m| m.get("itemSchema").is_some())
        .collect();
    match found.as_slice() {
        [] => Ok(None),
        [one] => Ok(Some(one)),
        _ => Err(Refusal(format!("{ptr}: more than one response streams events"))),
    }
}

fn op_id(op: &Value, ptr: &str) -> Result<String, Refusal> {
    op.get("operationId")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| Refusal(format!("{ptr}: a streamed operation has no operationId")))
}

fn parse_branches(item_schema: &Value, op_id: &str) -> Result<Vec<Branch>, Refusal> {
    let one_of = item_schema
        .as_object()
        .filter(|o| has_exactly(o, &["oneOf"]))
        .and_then(|o| o["oneOf"].as_array())
        .filter(|a| !a.is_empty())
        .ok_or_else(|| Refusal(format!("operation {op_id}: itemSchema is not a oneOf of event branches")))?;
    one_of
        .iter()
        .enumerate()
        .map(|(i, b)| {
            parse_branch(b).ok_or_else(|| {
                Refusal(format!(
                    "operation {op_id}: itemSchema branch {i} is not the event-const + contentSchema-$ref shape"
                ))
            })
        })
        .collect()
}

/// `{type: object, required: [event, data], properties: {event: {const},
/// data: {contentMediaType: application/json, contentSchema: {$ref}}}}`,
/// and nothing else.
fn parse_branch(b: &Value) -> Option<Branch> {
    let o = b.as_object().filter(|o| has_exactly(o, &["type", "required", "properties"]))?;
    let required: Vec<&str> = o["required"].as_array()?.iter().filter_map(Value::as_str).collect();
    if o["type"] != "object" || required.len() != 2 || !required.contains(&"event") || !required.contains(&"data") {
        return None;
    }
    let props = o["properties"].as_object().filter(|p| has_exactly(p, &["event", "data"]))?;
    let event = props["event"].as_object().filter(|e| has_exactly(e, &["const"]))?;
    let data = props["data"]
        .as_object()
        .filter(|d| has_exactly(d, &["contentMediaType", "contentSchema"]))
        .filter(|d| d["contentMediaType"] == "application/json")?;
    let data_ref = data["contentSchema"]
        .as_object()
        .filter(|c| has_exactly(c, &["$ref"]))?["$ref"]
        .as_str()
        .filter(|r| r.starts_with(SCHEMA_PREFIX))?;
    Some(Branch { event: event["const"].as_str()?.to_owned(), data_ref: data_ref.to_owned() })
}

fn has_exactly(o: &Map<String, Value>, keys: &[&str]) -> bool {
    o.len() == keys.len() && keys.iter().all(|k| o.contains_key(*k))
}

/// The union's name: `generateVocabulary` → `GenerateVocabularyEvent`.
pub fn union_name(op_id: &str) -> String {
    let mut chars = op_id.chars();
    let head: String = chars.next().map(|c| c.to_uppercase().collect()).unwrap_or_default();
    format!("{head}{}Event", chars.as_str())
}

/// An event name as a schema-name suffix: `started` → `Started`,
/// `tool_call` → `ToolCall`. `None` when it cannot be one.
fn pascal(event: &str) -> Option<String> {
    let mut out = String::new();
    for word in event.split(['_', '-', '.']).filter(|w| !w.is_empty()) {
        let mut chars = word.chars();
        out.extend(chars.next()?.to_uppercase());
        out.push_str(chars.as_str());
    }
    (!out.is_empty() && out.chars().all(|c| c.is_ascii_alphanumeric())).then_some(out)
}

pub(crate) fn events(s: &Stream) -> Vec<&str> {
    s.branches.iter().map(|b| b.event.as_str()).collect()
}

/// The branch schemas, then the union, in source order.
fn named_schemas(s: &Stream) -> Result<Vec<(String, Value)>, Refusal> {
    let union = union_name(&s.op_id);
    let mut out = Vec::new();
    let mut refs = Vec::new();
    let mut mapping = Map::new();
    for b in &s.branches {
        let suffix = pascal(&b.event)
            .ok_or_else(|| Refusal(format!("operation {}: event {:?} cannot name a schema", s.op_id, b.event)))?;
        let name = format!("{union}{suffix}");
        let target = format!("{SCHEMA_PREFIX}{name}");
        if mapping.insert(b.event.clone(), json!(target)).is_some() {
            return Err(Refusal(format!("operation {}: event {:?} appears twice", s.op_id, b.event)));
        }
        refs.push(json!({ "$ref": target }));
        out.push((name, branch_schema(b)));
    }
    let discriminator = json!({ "propertyName": "event", "mapping": mapping });
    out.push((union, json!({ "oneOf": refs, "discriminator": discriminator })));
    Ok(out)
}

/// `const` becomes a one-value `enum`; `data` is the `contentSchema` itself,
/// because the core decodes the SSE `data` line as JSON before the generated
/// type sees it.
fn branch_schema(b: &Branch) -> Value {
    json!({
        "type": "object",
        "required": ["event", "data"],
        "properties": {
            "event": { "type": "string", "enum": [b.event] },
            "data": { "$ref": b.data_ref },
        },
    })
}

fn insert_schemas(doc: &mut Value, named: Vec<(String, Value)>) -> Result<(), Refusal> {
    let schemas = doc
        .pointer_mut("/components/schemas")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| Refusal("/components/schemas: missing, so no stream can be lifted".into()))?;
    for (name, schema) in named {
        if schemas.contains_key(&name) {
            return Err(Refusal(format!(
                "lifted schema {name} collides with an existing component schema"
            )));
        }
        schemas.insert(name, schema);
    }
    Ok(())
}

/// Remove the operation, and its path item once no operation is left in it.
/// Path-level `parameters`, `summary` or `description` do not keep it.
fn strip(doc: &mut Value, s: &Stream) {
    let Some(paths) = doc.get_mut("paths").and_then(Value::as_object_mut) else { return };
    let Some(item) = paths.get_mut(&s.path).and_then(Value::as_object_mut) else { return };
    item.shift_remove(&s.method);
    let has_op = METHODS.iter().any(|m| item.contains_key(*m)) || item.contains_key("additionalOperations");
    if !has_op {
        paths.shift_remove(&s.path);
    }
}
