//! The event catalogue enters the generator view (ADR 30.9.26aa D2).
//!
//! The AsyncAPI document is read as a closed subset of 3.0 (`catalogue`),
//! its schemas merged into the view, each defined once (`merge`), and one
//! `x-lingara-events` entry written per message:
//! the wire type, its direction, the `data` component, and the doors that
//! carry it. Each payload's envelope is checked and then discarded, as
//! `lift` discards a stream branch's `event` const: every emitter writes the
//! tag from the record, and the 3.0 dialect has no `const`.

use std::collections::BTreeSet;

use serde_json::{json, Map, Value};

mod catalogue;
mod merge;

#[cfg(test)]
pub(crate) mod events_tests;

use catalogue::{read, ref_pointer, Message};
use merge::{drop_inbound_request, merge_schemas, tag_value};

use crate::walk::escape;
use crate::Refusal;

const SCHEMAS: &str = "/components/schemas/";

/// The outbound payload is `EventEnvelope` with a typed `type` and `data`.
const OUTBOUND: [&str; 6] = ["id", "type", "created_at", "api_version", "subject", "data"];
/// The inbound payload is `sendEvent`'s body: `{type, data}` and no more.
const INBOUND: [&str; 2] = ["type", "data"];
/// D3 generates these three names in every library.
const RESERVED: [&str; 3] = ["Event", "UnknownEvent", "InboundEvent"];
const PAYLOAD_DOCS: [&str; 4] = ["title", "description", "x-i18n", "examples"];

/// One `x-lingara-events` entry, before it is rendered.
struct Entry {
    name: String,
    outbound: bool,
    data: String,
    transports: BTreeSet<String>,
}

/// Merge `asyncapi`'s catalogue into the lifted `doc`, whose pre-lift form
/// is `original`, and write `x-lingara-events`.
pub fn events(doc: &mut Value, original: &Value, asyncapi: &Value) -> Result<(), Refusal> {
    let (catalogue, messages) = read(asyncapi)?;
    let entries = messages.iter().map(entry).collect::<Result<Vec<_>, _>>()?;
    merge_schemas(doc, original, &catalogue)?;
    let records = records(doc, &entries)?;
    let inbound: Vec<(&str, &str)> =
        entries.iter().filter(|e| !e.outbound).map(|e| (e.name.as_str(), component(&e.data))).collect();
    if !inbound.is_empty() {
        let address = inbound_address(&catalogue)?;
        drop_inbound_request(doc, &address, &inbound)?;
    }
    if let Some(root) = doc.as_object_mut() {
        root.insert("x-lingara-events".into(), Value::Array(records));
    }
    Ok(())
}

/// The view of a document with no catalogue: an empty one, unless its paths
/// already name the events routes, whose types would then be missing.
pub fn refuse_events_without_catalogue(spec: &Value) -> Result<(), Refusal> {
    let paths = spec.get("paths").and_then(Value::as_object).into_iter().flatten();
    match paths.map(|(p, _)| p).find(|p| *p == "/v1/events" || p.starts_with("/v1/events/")) {
        Some(p) => Err(Refusal(format!("/paths/{}: an events route with no AsyncAPI catalogue beside it", escape(p)))),
        None => Ok(()),
    }
}

fn component(data_ref: &str) -> &str {
    data_ref.strip_prefix(&format!("#{SCHEMAS}")).unwrap_or(data_ref)
}

/// The payload must be exactly its direction's shape (S3); its tag must be
/// the message's name.
fn entry(m: &Message) -> Result<Entry, Refusal> {
    let ptr = format!("/components/messages/{}/payload", m.key);
    let bad = |why: &str| Refusal(format!("{ptr}: {why}"));
    let o = m.payload.as_object().ok_or_else(|| bad("not an inline object schema"))?;
    if o.keys().any(|k| !["type", "required", "properties"].contains(&k.as_str()) && !PAYLOAD_DOCS.contains(&k.as_str()))
        || o.get("type").is_none_or(|t| t != "object")
    {
        return Err(bad("not an object schema with only type, required and properties"));
    }
    let want: &[&str] = if m.outbound { &OUTBOUND } else { &INBOUND };
    let props = o.get("properties").and_then(Value::as_object).ok_or_else(|| bad("no properties"))?;
    let required: BTreeSet<&str> = o.get("required").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).collect();
    let keys: BTreeSet<&str> = props.keys().map(String::as_str).collect();
    let want_set: BTreeSet<&str> = want.iter().copied().collect();
    if keys != want_set || required != want_set {
        return Err(bad(&format!("its properties and required are not exactly {want:?}")));
    }
    let tag = props["type"].as_object().and_then(tag_value).ok_or_else(|| bad("type is not a string const"))?;
    if tag != m.name {
        return Err(bad(&format!("type is {tag:?}, not the message name {:?}", m.name)));
    }
    let data = props["data"].as_object().filter(|d| d.len() == 1).and_then(|_| ref_pointer(&props["data"]));
    let data = data.filter(|p| p.starts_with(SCHEMAS)).ok_or_else(|| bad("data is not a $ref to a component schema"))?;
    Ok(Entry { name: m.name.clone(), outbound: m.outbound, data: format!("#{data}"), transports: m.transports.clone() })
}

/// `lesson_plan.ready` → `LessonPlanReady`.
pub fn arm(name: &str) -> Option<String> {
    let mut out = String::new();
    for word in name.split(['.', '_']).filter(|w| !w.is_empty()) {
        let mut chars = word.chars();
        out.extend(chars.next()?.to_uppercase());
        out.push_str(chars.as_str());
    }
    (!out.is_empty() && out.chars().all(|c| c.is_ascii_alphanumeric())).then_some(out)
}

fn records(doc: &Value, entries: &[Entry]) -> Result<Vec<Value>, Refusal> {
    let schemas = doc.pointer("/components/schemas").and_then(Value::as_object).cloned().unwrap_or_default();
    if let Some(r) = RESERVED.iter().find(|r| schemas.contains_key(**r)) {
        return Err(Refusal(format!("{SCHEMAS}{r}: a component may not take a name D3 generates")));
    }
    let mut names = BTreeSet::new();
    entries.iter().map(|e| record(e, &schemas, &mut names)).collect()
}

/// Outbound entries carry `arm`; an inbound one carries none, since its
/// constructor is named from its `data` component, never from `name`.
fn record(e: &Entry, schemas: &Map<String, Value>, names: &mut BTreeSet<String>) -> Result<Value, Refusal> {
    let data = component(&e.data);
    if !schemas.contains_key(data) {
        return Err(Refusal(format!("event {:?}: its data {} is not a component", e.name, e.data)));
    }
    if !e.outbound {
        if !names.insert(data.to_owned()) {
            return Err(Refusal(format!("event {:?}: another inbound event already uses {data}", e.name)));
        }
        return Ok(json!({ "type": e.name, "direction": "in", "data": e.data, "transports": e.transports }));
    }
    let arm = arm(&e.name).ok_or_else(|| Refusal(format!("event {:?} cannot name an arm", e.name)))?;
    if RESERVED.contains(&arm.as_str()) || schemas.contains_key(&arm) || !names.insert(arm.clone()) {
        return Err(Refusal(format!("event {:?}: its arm {arm} collides with a component or another arm", e.name)));
    }
    Ok(json!({ "type": e.name, "direction": "out", "arm": arm, "data": e.data, "transports": e.transports }))
}

/// The inbound channel's `address`: the path `sendEvent` posts to.
fn inbound_address(catalogue: &Value) -> Result<String, Refusal> {
    let channels = catalogue.get("channels").and_then(Value::as_object).into_iter().flatten();
    let inbound = channels.map(|(_, c)| c).find(|c| c["x-lingara-transport"] == "inbound");
    inbound
        .and_then(|c| c.get("address")?.as_str())
        .map(str::to_owned)
        .ok_or_else(|| Refusal("/channels: the inbound channel has no address".into()))
}
