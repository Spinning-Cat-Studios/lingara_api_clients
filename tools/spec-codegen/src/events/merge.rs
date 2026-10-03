//! What the catalogue changes in the view's components (ADR 30.9.26aa D2):
//! its schemas are merged, each defined once, and `InboundEventRequest`, the
//! inbound union in OpenAPI's words, leaves the view as the stream unions'
//! `oneOf`s do.

use serde_json::{json, Map, Value};

use super::catalogue::ref_pointer;
use crate::Refusal;

const SCHEMAS: &str = "/components/schemas/";
const INBOUND_REQUEST: &str = "InboundEventRequest";

/// Merge every catalogue schema into `doc`. A name the OpenAPI snapshot
/// already has must be the same schema (compared before `lift` rewrote
/// anything, so `original` is the snapshot as read); an absent one is added.
pub(crate) fn merge_schemas(doc: &mut Value, original: &Value, catalogue: &Value) -> Result<(), Refusal> {
    refuse_external_refs(catalogue, "")?;
    let theirs = catalogue.pointer("/components/schemas").and_then(Value::as_object).cloned().unwrap_or_default();
    let ours = original.pointer("/components/schemas").and_then(Value::as_object);
    let schemas = doc
        .pointer_mut("/components/schemas")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| Refusal("/components/schemas: missing, so no event schema can be merged".into()))?;
    for (name, schema) in theirs {
        match ours.and_then(|o| o.get(&name)) {
            // serde_json's map equality ignores key order: canonical equality.
            Some(existing) if *existing == schema => {}
            Some(_) => {
                return Err(Refusal(format!(
                    "/components/schemas/{name}: the catalogue's schema differs from the OpenAPI document's"
                )));
            }
            None => {
                schemas.insert(name, schema);
            }
        }
    }
    Ok(())
}

/// E1's bundle is self-contained, so every `$ref` is internal.
fn refuse_external_refs(v: &Value, ptr: &str) -> Result<(), Refusal> {
    match v {
        Value::Object(o) => {
            if let Some(r) = o.get("$ref").and_then(Value::as_str).filter(|r| !r.starts_with("#/")) {
                return Err(Refusal(format!("{ptr}/$ref: {r:?} is not internal to the catalogue")));
            }
            o.iter().try_for_each(|(k, c)| refuse_external_refs(c, &format!("{ptr}/{k}")))
        }
        Value::Array(a) => a.iter().enumerate().try_for_each(|(i, c)| refuse_external_refs(c, &format!("{ptr}/{i}"))),
        _ => Ok(()),
    }
}

/// `(type, data component)` for each inbound entry, in record order.
pub(crate) type Inbound<'a> = [(&'a str, &'a str)];

/// Replace `sendEvent`'s body, a `$ref` to `InboundEventRequest`, with
/// `{type: object}` and remove the component, once its members are shown to
/// be exactly the inbound entries. The hand-written `sendEvent` serialises
/// D3's `InboundEvent` instead.
pub(crate) fn drop_inbound_request(doc: &mut Value, address: &str, inbound: &Inbound) -> Result<(), Refusal> {
    let item = format!("/paths/{}/post", address.replace('~', "~0").replace('/', "~1"));
    let ptr = format!("{item}/requestBody/content/application~1json/schema");
    let body = doc.pointer(&ptr).ok_or_else(|| Refusal(format!("{item}: the inbound door has no JSON request body")))?;
    if ref_pointer(body) != Some(&format!("{SCHEMAS}{INBOUND_REQUEST}")[..]) || body.as_object().is_some_and(|o| o.len() != 1) {
        return Err(Refusal(format!("{ptr}: not a single $ref to {INBOUND_REQUEST}")));
    }
    let union = doc.pointer(&format!("{SCHEMAS}{INBOUND_REQUEST}")).ok_or_else(|| {
        Refusal(format!("{SCHEMAS}{INBOUND_REQUEST}: missing"))
    })?;
    let members = union_members(union)?;
    let mut want: Vec<(&str, &str)> = inbound.to_vec();
    let mut got: Vec<(&str, &str)> = members.iter().map(|(t, d)| (t.as_str(), d.as_str())).collect();
    want.sort_unstable();
    got.sort_unstable();
    if got != want {
        return Err(Refusal(format!(
            "{SCHEMAS}{INBOUND_REQUEST}: its members {got:?} are not the catalogue's inbound events {want:?}"
        )));
    }
    *doc.pointer_mut(&ptr).expect("read above") = json!({ "type": "object" });
    if let Some(schemas) = doc.pointer_mut("/components/schemas").and_then(Value::as_object_mut) {
        schemas.shift_remove(INBOUND_REQUEST);
    }
    if doc.to_string().contains(&format!("\"#{SCHEMAS}{INBOUND_REQUEST}\"")) {
        return Err(Refusal(format!("{INBOUND_REQUEST} is still referenced after sendEvent's body left the view")));
    }
    Ok(())
}

/// `oneOf` of `{type: object, required: [type, data], properties: {type:
/// <one-value enum or const>, data: {$ref}}}`: `(type, data component)`.
fn union_members(union: &Value) -> Result<Vec<(String, String)>, Refusal> {
    let bad = |i: usize| Refusal(format!("{SCHEMAS}{INBOUND_REQUEST}/oneOf/{i}: not the {{type, data}} member shape"));
    let one_of = union
        .as_object()
        .filter(|o| o.keys().all(|k| k == "oneOf" || k == "description" || k == "x-i18n"))
        .and_then(|o| o.get("oneOf")?.as_array())
        .ok_or_else(|| Refusal(format!("{SCHEMAS}{INBOUND_REQUEST}: not a oneOf")))?;
    one_of.iter().enumerate().map(|(i, m)| member(m).ok_or_else(|| bad(i))).collect()
}

fn member(m: &Value) -> Option<(String, String)> {
    let o = m.as_object()?;
    if o.keys().any(|k| !["type", "required", "properties"].contains(&k.as_str())) || o.get("type")? != "object" {
        return None;
    }
    let mut required: Vec<&str> = o.get("required")?.as_array()?.iter().filter_map(Value::as_str).collect();
    required.sort_unstable();
    let props = o.get("properties")?.as_object()?;
    if required != ["data", "type"] || props.len() != 2 {
        return None;
    }
    let tag = tag_value(props.get("type")?.as_object()?)?;
    let data = ref_pointer(props.get("data")?)?.strip_prefix(SCHEMAS)?;
    (props["data"].as_object()?.len() == 1).then(|| (tag, data.to_owned()))
}

/// A one-value string `enum`, or a `const` (so a schemars change of
/// spelling is not a break).
pub(crate) fn tag_value(t: &Map<String, Value>) -> Option<String> {
    if t.keys().any(|k| !["type", "enum", "const"].contains(&k.as_str())) || t.get("type").is_some_and(|v| v != "string") {
        return None;
    }
    match (t.get("const"), t.get("enum").and_then(Value::as_array)) {
        (Some(Value::String(c)), None) => Some(c.clone()),
        (None, Some(e)) if e.len() == 1 => e[0].as_str().map(str::to_owned),
        _ => None,
    }
}
