//! Reading the AsyncAPI 3.0 catalogue (ADR 30.9.26aa D2): the `app`
//! transport is skipped whole, the rest must sit inside a closed subset, and
//! every message comes out with its direction and the doors that carry it.

use std::collections::{BTreeSet, HashSet};

use serde_json::{Map, Value};

use crate::Refusal;

/// The doors E1 names. `app` is not one of them: it is skipped before the
/// subset is checked (roadmap 30.9.26ae), and is the app kits' contract.
const TRANSPORTS: [&str; 4] = ["webhook", "feed", "stream", "inbound"];
const APP: &str = "app";

/// Keys that say nothing a library acts on, dropped wherever they appear.
const DOCS: [&str; 6] = ["title", "summary", "description", "tags", "externalDocs", "x-i18n"];
const ROOT_KEYS: [&str; 6] = ["asyncapi", "info", "servers", "defaultContentType", "channels", "operations"];
const CHANNEL_KEYS: [&str; 4] = ["address", "messages", "x-lingara-transport", "servers"];
const OPERATION_KEYS: [&str; 4] = ["action", "channel", "messages", "security"];
const MESSAGE_KEYS: [&str; 4] = ["name", "payload", "contentType", "examples"];
const COMPONENT_KEYS: [&str; 3] = ["schemas", "messages", "securitySchemes"];
const JSON: &str = "application/json";

/// One message of the catalogue, in `components.messages` order.
pub(crate) struct Message {
    pub(crate) key: String,
    pub(crate) name: String,
    pub(crate) outbound: bool,
    pub(crate) transports: BTreeSet<String>,
    pub(crate) payload: Value,
}

/// The catalogue with `app` skipped, checked against the subset, and read
/// into its messages.
pub(crate) fn read(asyncapi: &Value) -> Result<(Value, Vec<Message>), Refusal> {
    let mut doc = asyncapi.clone();
    skip_app(&mut doc);
    check_subset(&doc)?;
    let messages = messages(&doc)?;
    Ok((doc, messages))
}

fn obj<'a>(v: &'a Value, ptr: &str) -> Result<&'a Map<String, Value>, Refusal> {
    v.as_object().ok_or_else(|| Refusal(format!("{ptr}: not an object")))
}

fn only(o: &Map<String, Value>, allowed: &[&str], ptr: &str) -> Result<(), Refusal> {
    match o.keys().find(|k| !allowed.contains(&k.as_str()) && !DOCS.contains(&k.as_str())) {
        Some(k) => Err(Refusal(format!("{ptr}/{k}: outside the AsyncAPI subset the view reads"))),
        None => Ok(()),
    }
}

fn transport(channel: &Value) -> Option<&str> {
    channel.get("x-lingara-transport").and_then(Value::as_str)
}

/// The `$ref` target of `{"$ref": "#/…"}`, as a JSON pointer.
pub(crate) fn ref_pointer(v: &Value) -> Option<&str> {
    v.get("$ref")?.as_str()?.strip_prefix('#')
}

/// Every message a pointer chain reaches, following `$ref`s to the end.
fn resolve<'a>(doc: &'a Value, mut v: &'a Value) -> Option<(&'a str, &'a Value)> {
    let mut at = "";
    for _ in 0..8 {
        let Some(ptr) = ref_pointer(v) else { return Some((at, v)) };
        at = ptr;
        v = doc.pointer(ptr)?;
    }
    None
}

/// Remove the `app` channels, the operations on them, the messages their
/// maps reach, and every schema reachable only from those messages.
fn skip_app(doc: &mut Value) {
    let channels = doc.get("channels").and_then(Value::as_object).cloned().unwrap_or_default();
    let app: Vec<String> = channels.iter().filter(|(_, c)| transport(c) == Some(APP)).map(|(k, _)| k.clone()).collect();
    if app.is_empty() {
        return;
    }
    let message_of = |c: &Value| -> Vec<String> {
        let refs = c.get("messages").and_then(Value::as_object).into_iter().flatten();
        refs.filter_map(|(_, m)| resolve(doc, m)).filter_map(|(p, _)| p.strip_prefix("/components/messages/")).map(str::to_owned).collect()
    };
    let app_messages: HashSet<String> = app.iter().flat_map(|k| message_of(&channels[k])).collect();
    let kept_messages: HashSet<String> =
        channels.iter().filter(|(k, _)| !app.contains(k)).flat_map(|(_, c)| message_of(c)).collect();
    let schemas_of = |names: &HashSet<String>| reachable(doc, names.iter().map(|m| &doc["components"]["messages"][m]));
    let (app_schemas, kept_schemas) = (schemas_of(&app_messages), schemas_of(&kept_messages));

    let ops = doc.get_mut("operations").and_then(Value::as_object_mut);
    let app_refs: Vec<String> = app.iter().map(|k| format!("#/channels/{k}")).collect();
    ops.into_iter().for_each(|o| o.retain(|_, op| !app_refs.iter().any(|r| op["channel"]["$ref"] == r.as_str())));
    let chans = doc.get_mut("channels").and_then(Value::as_object_mut);
    chans.into_iter().for_each(|c| c.retain(|k, _| !app.contains(k)));
    remove_keys(doc, "/components/messages", |k| app_messages.contains(k) && !kept_messages.contains(k));
    remove_keys(doc, "/components/schemas", |k| app_schemas.contains(k) && !kept_schemas.contains(k));
}

fn remove_keys(doc: &mut Value, ptr: &str, drop: impl Fn(&str) -> bool) {
    if let Some(map) = doc.pointer_mut(ptr).and_then(Value::as_object_mut) {
        map.retain(|k, _| !drop(k));
    }
}

/// The component schema names reachable from `roots`, transitively.
pub(crate) fn reachable<'a>(doc: &Value, roots: impl Iterator<Item = &'a Value>) -> HashSet<String> {
    let mut seen = HashSet::new();
    let mut stack: Vec<&Value> = roots.collect();
    while let Some(v) = stack.pop() {
        match v {
            Value::Object(o) => {
                let name = ref_pointer(v).and_then(|p| p.strip_prefix("/components/schemas/"));
                if let Some(name) = name.filter(|n| seen.insert(n.to_string())) {
                    stack.extend(doc.pointer(&format!("/components/schemas/{name}")));
                }
                stack.extend(o.values());
            }
            Value::Array(a) => stack.extend(a),
            _ => {}
        }
    }
    seen
}

fn check_subset(doc: &Value) -> Result<(), Refusal> {
    refuse_status(doc, "")?;
    let root = obj(doc, "")?;
    only(root, &[&ROOT_KEYS[..], &["components"]].concat(), "")?;
    match root.get("asyncapi").and_then(Value::as_str) {
        Some(v) if v.starts_with("3.0.") => {}
        other => return Err(Refusal(format!("/asyncapi: {other:?} is not a 3.0 document"))),
    }
    if root.get("defaultContentType").is_some_and(|c| c != JSON) {
        return Err(Refusal("/defaultContentType: not application/json".into()));
    }
    let components = obj(root.get("components").unwrap_or(&Value::Null), "/components")?;
    only(components, &COMPONENT_KEYS, "/components")?;
    for (section, allowed) in [("channels", &CHANNEL_KEYS[..]), ("operations", &OPERATION_KEYS), ("components/messages", &MESSAGE_KEYS)] {
        let ptr = format!("/{section}");
        for (k, v) in obj(doc.pointer(&ptr).unwrap_or(&Value::Null), &ptr)? {
            only(obj(v, &format!("{ptr}/{k}"))?, allowed, &format!("{ptr}/{k}"))?;
        }
    }
    for (k, c) in doc["channels"].as_object().into_iter().flatten() {
        if !transport(c).is_some_and(|t| TRANSPORTS.contains(&t)) {
            let t = transport(c);
            return Err(Refusal(format!("/channels/{k}/x-lingara-transport: {t:?} is not one of {TRANSPORTS:?}")));
        }
        if !c.get("address").is_some_and(|a| a.is_string() || a.is_null()) {
            return Err(Refusal(format!("/channels/{k}/address: neither a string nor null")));
        }
    }
    Ok(())
}

/// No frozen document may carry `x-lingara-status` (E1 mints only after
/// every door is open), so one that does was minted half-open.
fn refuse_status(v: &Value, ptr: &str) -> Result<(), Refusal> {
    match v {
        Value::Object(o) if o.contains_key("x-lingara-status") => {
            Err(Refusal(format!("{ptr}/x-lingara-status: a frozen catalogue carries no planned door")))
        }
        Value::Object(o) => o.iter().try_for_each(|(k, c)| refuse_status(c, &format!("{ptr}/{k}"))),
        Value::Array(a) => a.iter().enumerate().try_for_each(|(i, c)| refuse_status(c, &format!("{ptr}/{i}"))),
        _ => Ok(()),
    }
}

/// Every message, in `components.messages` order, with its direction from
/// the operations that name it and its transports from the channels.
fn messages(doc: &Value) -> Result<Vec<Message>, Refusal> {
    let mut out = Vec::new();
    for (key, m) in doc["components"]["messages"].as_object().into_iter().flatten() {
        let ptr = format!("/components/messages/{key}");
        if m.get("contentType").is_some_and(|c| c != JSON) {
            return Err(Refusal(format!("{ptr}/contentType: not application/json")));
        }
        let name = m.get("name").and_then(Value::as_str).ok_or_else(|| Refusal(format!("{ptr}/name: missing")))?;
        let payload = m.get("payload").cloned().ok_or_else(|| Refusal(format!("{ptr}/payload: missing")))?;
        let (outbound, transports) = direction(doc, key)?;
        out.push(Message { key: key.clone(), name: name.to_owned(), outbound, transports, payload });
    }
    Ok(out)
}

/// `send` is Lingara sending, so `out`; `receive` is `in` (AsyncAPI 3.0's
/// application-perspective rule). Every operation naming the message must
/// agree, and so must its channel's transport.
fn direction(doc: &Value, key: &str) -> Result<(bool, BTreeSet<String>), Refusal> {
    let target = format!("/components/messages/{key}");
    let mut seen = BTreeSet::new();
    let mut transports = BTreeSet::new();
    for (op_key, op) in doc["operations"].as_object().into_iter().flatten() {
        let ptr = format!("/operations/{op_key}");
        let outbound = match op.get("action").and_then(Value::as_str) {
            Some("send") => true,
            Some("receive") => false,
            other => return Err(Refusal(format!("{ptr}/action: {:?} is not send or receive", other.unwrap_or("")))),
        };
        let channel_ptr = ref_pointer(&op["channel"]).ok_or_else(|| Refusal(format!("{ptr}/channel: not a $ref")))?;
        let channel = doc.pointer(channel_ptr).ok_or_else(|| Refusal(format!("{ptr}/channel: {channel_ptr} does not resolve")))?;
        let t = transport(channel).filter(|t| TRANSPORTS.contains(t)).ok_or_else(|| {
            Refusal(format!("{channel_ptr}/x-lingara-transport: {:?} is not one of {TRANSPORTS:?}", transport(channel)))
        })?;
        if outbound == (t == "inbound") {
            return Err(Refusal(format!("{ptr}: a {} operation on the {t} channel", if outbound { "send" } else { "receive" })));
        }
        let refs = op.get("messages").and_then(Value::as_array).ok_or_else(|| Refusal(format!("{ptr}/messages: not a list")))?;
        for r in refs {
            let at = ref_pointer(r).filter(|p| p.starts_with(&format!("{channel_ptr}/messages/")));
            let at = at.ok_or_else(|| Refusal(format!("{ptr}/messages: not a $ref into {channel_ptr}")))?;
            let (resolved, _) = resolve(doc, r).ok_or_else(|| Refusal(format!("{ptr}/messages: {at} does not resolve")))?;
            if resolved == target {
                seen.insert(outbound);
                transports.insert(t.to_owned());
            }
        }
    }
    match (seen.len(), seen.first()) {
        (1, Some(out)) => Ok((*out, transports)),
        (0, _) => Err(Refusal(format!("{target}: no operation sends or receives it"))),
        _ => Err(Refusal(format!("{target}: both sent and received"))),
    }
}
