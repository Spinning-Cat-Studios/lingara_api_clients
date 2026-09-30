//! The 3.0 dialect, written from the finished 3.1 view (ADR 29.9.26m D4a).
//!
//! Some generators read 3.0 better than 3.1, so the view is also written in
//! 3.0.3. It is a second, mechanical pass over Schema Objects only: the two
//! nullable forms the bundle uses are rewritten, and every form 3.0 cannot
//! say is refused with its JSON pointer rather than approximated.

use serde_json::{Map, Value};

use crate::walk::{sites, Kind};
use crate::Refusal;

/// Schema keywords with no 3.0 spelling.
pub const SCHEMA_KEYS_31: [&str; 10] = [
    "const",
    "examples",
    "prefixItems",
    "$defs",
    "if",
    "then",
    "else",
    "dependentSchemas",
    "unevaluatedItems",
    "unevaluatedProperties",
];

/// Document-level 3.1 fields, each where the object model puts it.
pub const FIELDS_31: [(Kind, &str); 5] = [
    (Kind::Document, "webhooks"),
    (Kind::Document, "jsonSchemaDialect"),
    (Kind::Components, "pathItems"),
    (Kind::Info, "summary"),
    (Kind::License, "identifier"),
];

/// The 3.0.3 dialect of a 3.1 view.
pub fn dialect30(view31: &Value) -> Result<Value, Refusal> {
    let mut doc = view31.clone();
    doc["openapi"] = Value::from("3.0.3");
    rewrite_schemas(&mut doc)?;
    refuse_31(&doc)?;
    Ok(doc)
}

fn rewrite_schemas(doc: &mut Value) -> Result<(), Refusal> {
    for site in sites(doc).into_iter().filter(|s| s.kind == Kind::Schema) {
        // A rewritten parent's `anyOf` children no longer resolve: skipped.
        let Some(schema) = doc.pointer(&site.pointer).and_then(Value::as_object) else { continue };
        if let Some(new) = rewrite_one(doc, schema, &site.pointer)? {
            if let Some(slot) = doc.pointer_mut(&site.pointer) {
                *slot = Value::Object(new);
            }
        }
    }
    Ok(())
}

fn rewrite_one(doc: &Value, s: &Map<String, Value>, ptr: &str) -> Result<Option<Map<String, Value>>, Refusal> {
    if let Some(target) = nullable_ref(s) {
        return nullable_ref_30(doc, s, target, ptr).map(Some);
    }
    match s.get("type") {
        Some(Value::Array(types)) => type_array_30(s, types, ptr).map(Some),
        Some(Value::String(t)) if t == "null" => Err(Refusal(format!("{ptr}/type: a lone null type has no 3.0 form"))),
        _ => Ok(None),
    }
}

/// `anyOf: [{$ref}, {type: 'null'}]`, in either order: the `$ref`.
fn nullable_ref(s: &Map<String, Value>) -> Option<&str> {
    let [a, b] = s.get("anyOf")?.as_array()?.as_slice() else { return None };
    let is_null = |v: &Value| v.as_object().is_some_and(|o| o.len() == 1 && o.get("type") == Some(&Value::from("null")));
    match (ref_only(a), ref_only(b)) {
        (Some(r), None) if is_null(b) => Some(r),
        (None, Some(r)) if is_null(a) => Some(r),
        _ => None,
    }
}

/// `{$ref}` and nothing else: the `$ref`.
fn ref_only(v: &Value) -> Option<&str> {
    v.as_object().filter(|o| o.len() == 1)?.get("$ref")?.as_str()
}

/// `type: <the target's type>, nullable: true, allOf: [{$ref}]`, in the
/// `anyOf`'s place. 3.0 ignores a `$ref`'s siblings, hence the `allOf`;
/// 3.0.3 gives `nullable` effect only beside a `type`, hence the copy.
fn nullable_ref_30(doc: &Value, s: &Map<String, Value>, target: &str, ptr: &str) -> Result<Map<String, Value>, Refusal> {
    let target_type = target
        .strip_prefix('#')
        .and_then(|p| doc.pointer(p))
        .and_then(|t| t.get("type"))
        .and_then(Value::as_str)
        .filter(|t| *t != "null")
        .ok_or_else(|| Refusal(format!("{ptr}/anyOf: {target} has no single type for nullable to sit beside")))?;
    if s.contains_key("type") {
        return Err(Refusal(format!("{ptr}/type: a nullable $ref already beside a type")));
    }
    let mut out = Map::new();
    for (k, v) in s {
        if k == "anyOf" {
            out.insert("type".into(), Value::from(target_type));
            out.insert("nullable".into(), Value::Bool(true));
            out.insert("allOf".into(), serde_json::json!([{ "$ref": target }]));
        } else {
            out.insert(k.clone(), v.clone());
        }
    }
    Ok(out)
}

/// `type: [t, 'null']` → `type: t, nullable: true`; `[t]` → `t`.
fn type_array_30(s: &Map<String, Value>, types: &[Value], ptr: &str) -> Result<Map<String, Value>, Refusal> {
    let has_null = types.iter().any(|t| t == "null");
    let [single] = types.iter().filter(|t| *t != "null").collect::<Vec<_>>()[..] else {
        return Err(Refusal(format!("{ptr}/type: {} has no 3.0 form", Value::from(types.to_vec()))));
    };
    let mut out = Map::new();
    for (k, v) in s {
        if k == "type" {
            out.insert("type".into(), single.clone());
            if has_null {
                out.insert("nullable".into(), Value::Bool(true));
            }
        } else {
            out.insert(k.clone(), v.clone());
        }
    }
    Ok(out)
}

/// Refuse every 3.1-only form left after the rewrite.
pub fn refuse_31(doc: &Value) -> Result<(), Refusal> {
    for site in sites(doc) {
        let Some(obj) = doc.pointer(&site.pointer).and_then(Value::as_object) else { continue };
        let field = FIELDS_31.iter().find(|(k, key)| *k == site.kind && obj.contains_key(*key));
        if let Some((_, key)) = field {
            return Err(Refusal(format!("{}/{key}: a 3.1-only field", site.pointer)));
        }
        if site.kind == Kind::Schema {
            refuse_schema_31(obj, &site.pointer)?;
        }
    }
    Ok(())
}

fn refuse_schema_31(s: &Map<String, Value>, ptr: &str) -> Result<(), Refusal> {
    let refuse = |key: &str, why: &str| Err(Refusal(format!("{ptr}/{key}: {why}")));
    if let Some(key) = SCHEMA_KEYS_31.iter().find(|k| s.contains_key(**k)) {
        return refuse(key, "a 3.1-only schema keyword");
    }
    for key in ["exclusiveMinimum", "exclusiveMaximum"] {
        if s.get(key).is_some_and(Value::is_number) {
            return refuse(key, "a numeric exclusive bound is 3.1-only");
        }
    }
    if s.get("type").is_some_and(Value::is_array) {
        return refuse("type", "a type array is 3.1-only");
    }
    if s.contains_key("$ref") && s.len() > 1 {
        return refuse("$ref", "3.0 ignores a $ref's sibling keywords");
    }
    Ok(())
}
