//! OpenAPI 3.2 → 3.1, after the lift (ADR 29.9.26m D4).
//!
//! Two keys are dropped because a 3.1 reader loses nothing a library needs:
//! `oauth2MetadataUrl` (the token URL is still in the flow) and every
//! `x-i18n` (a library renders none of the spec's prose). Every other
//! 3.2-only key is refused with its JSON pointer, so a future Backend use of
//! one fails the view loudly instead of reaching a generator that misreads
//! it. Adding a key to the list is a change to this file, with a test.

use serde_json::Value;

use crate::walk::{sites, Kind};
use crate::Refusal;

/// The 3.2-only keys, each where the object model allows it. `itemSchema`
/// must already be gone: the lift consumes every one it recognises.
pub const KEYS_32: [(Kind, &str); 11] = [
    (Kind::Document, "$self"),
    (Kind::PathItem, "query"),
    (Kind::PathItem, "additionalOperations"),
    (Kind::MediaType, "itemSchema"),
    (Kind::MediaType, "itemEncoding"),
    (Kind::MediaType, "prefixEncoding"),
    (Kind::Example, "dataValue"),
    (Kind::Example, "serializedValue"),
    (Kind::Tag, "parent"),
    (Kind::Tag, "kind"),
    (Kind::Components, "mediaTypes"),
];

/// The keys dropped rather than refused, and where.
const DROPPED: [(Option<Kind>, &str); 2] = [(Some(Kind::SecurityScheme), "oauth2MetadataUrl"), (None, "x-i18n")];

/// The document's `openapi` version, when it is a 3.2 document.
pub fn check_version(doc: &Value) -> Result<String, Refusal> {
    match doc.get("openapi").and_then(Value::as_str) {
        Some(v) if v.starts_with("3.2.") => Ok(v.to_owned()),
        other => Err(Refusal(format!("/openapi: {other:?} is not a 3.2 document"))),
    }
}

/// Rewrite a lifted 3.2 document as 3.1 in place.
pub fn downconvert(doc: &mut Value) -> Result<(), Refusal> {
    doc["openapi"] = Value::from("3.1.0");
    for site in sites(doc) {
        let Some(obj) = doc.pointer_mut(&site.pointer).and_then(Value::as_object_mut) else { continue };
        for (kind, key) in DROPPED {
            if kind.is_none_or(|k| k == site.kind) {
                obj.shift_remove(key);
            }
        }
    }
    refuse_32(doc)
}

/// Refuse any 3.2-only key left anywhere the object model allows one.
pub fn refuse_32(doc: &Value) -> Result<(), Refusal> {
    for site in sites(doc) {
        let Some(obj) = doc.pointer(&site.pointer).and_then(Value::as_object) else { continue };
        for (kind, key) in KEYS_32 {
            if kind == site.kind && obj.contains_key(key) {
                return Err(Refusal(format!("{}/{key}: a 3.2-only key", site.pointer)));
            }
        }
        if site.kind == Kind::Parameter && obj.get("in").and_then(Value::as_str) == Some("querystring") {
            return Err(Refusal(format!("{}/in: querystring is a 3.2-only location", site.pointer)));
        }
    }
    Ok(())
}
