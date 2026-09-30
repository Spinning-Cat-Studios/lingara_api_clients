//! The structural walk both refusal passes share (ADR 29.9.26m D4).
//!
//! A walk reports an object only where the OpenAPI object model puts one:
//! the document, path items, operations, parameters, media types, security
//! schemes and Schema Objects. It never treats a `properties` map's keys as
//! keywords, and never descends into `example`, `examples` (as a schema
//! keyword), `default`, `const` or `enum` values, which may hold any JSON. So
//! a property named `kind` or an example holding `itemSchema` is data, not a
//! keyword, and passes every refusal.
//!
//! Sites are returned as JSON pointers rather than references, so a caller
//! can mutate through `Value::pointer_mut` with the same list.

use serde_json::{Map, Value};

/// What the OpenAPI object model says an object at a site is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Document,
    Info,
    License,
    Tag,
    Components,
    PathItem,
    Operation,
    Parameter,
    Header,
    RequestBody,
    Response,
    MediaType,
    Example,
    SecurityScheme,
    Schema,
}

/// One object in the document, located by its JSON pointer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Site {
    pub kind: Kind,
    pub pointer: String,
}

/// The HTTP methods a path item may hold as fixed fields (3.2 adds `query`).
pub const METHODS: [&str; 9] = [
    "get", "put", "post", "delete", "options", "head", "patch", "trace", "query",
];

/// Every site in `doc`, parents before children, in document order.
pub fn sites(doc: &Value) -> Vec<Site> {
    let mut w = Walker { out: Vec::new() };
    w.document(doc);
    w.out
}

/// Escape one JSON pointer reference token (RFC 6901).
pub fn escape(token: &str) -> String {
    token.replace('~', "~0").replace('/', "~1")
}

struct Walker {
    out: Vec<Site>,
}

impl Walker {
    fn push<'v>(&mut self, kind: Kind, v: &'v Value, ptr: &str) -> Option<&'v Map<String, Value>> {
        let obj = v.as_object()?;
        self.out.push(Site { kind, pointer: ptr.to_string() });
        Some(obj)
    }

    /// Visit the value at `obj[key]`.
    fn one(&mut self, obj: &Map<String, Value>, key: &str, ptr: &str, f: Visit) {
        if let Some(v) = obj.get(key) {
            f(self, v, &format!("{ptr}/{}", escape(key)));
        }
    }

    /// Visit every value of the map `obj[key]`.
    fn map(&mut self, obj: &Map<String, Value>, key: &str, ptr: &str, f: Visit) {
        if let Some(Value::Object(m)) = obj.get(key) {
            for (k, v) in m {
                f(self, v, &format!("{ptr}/{}/{}", escape(key), escape(k)));
            }
        }
    }

    /// Visit every element of the array `obj[key]`.
    fn list(&mut self, obj: &Map<String, Value>, key: &str, ptr: &str, f: Visit) {
        if let Some(Value::Array(a)) = obj.get(key) {
            for (i, v) in a.iter().enumerate() {
                f(self, v, &format!("{ptr}/{}/{i}", escape(key)));
            }
        }
    }

    fn document(&mut self, v: &Value) {
        let Some(o) = self.push(Kind::Document, v, "") else { return };
        self.one(o, "info", "", Self::info);
        self.list(o, "tags", "", |w, v, p| {
            w.push(Kind::Tag, v, p);
        });
        self.map(o, "paths", "", Self::path_item);
        self.map(o, "webhooks", "", Self::path_item);
        self.one(o, "components", "", Self::components);
    }

    fn info(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::Info, v, ptr) else { return };
        self.one(o, "license", ptr, |w, v, p| {
            w.push(Kind::License, v, p);
        });
    }

    fn components(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::Components, v, ptr) else { return };
        self.map(o, "schemas", ptr, Self::schema);
        self.map(o, "parameters", ptr, PARAMETER);
        self.map(o, "headers", ptr, HEADER);
        self.map(o, "responses", ptr, Self::response);
        self.map(o, "requestBodies", ptr, REQUEST_BODY);
        self.map(o, "mediaTypes", ptr, Self::media_type);
        self.map(o, "examples", ptr, EXAMPLE);
        self.map(o, "pathItems", ptr, Self::path_item);
        self.map(o, "securitySchemes", ptr, |w, v, p| {
            w.push(Kind::SecurityScheme, v, p);
        });
    }

    fn path_item(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::PathItem, v, ptr) else { return };
        self.list(o, "parameters", ptr, PARAMETER);
        for m in METHODS {
            self.one(o, m, ptr, Self::operation);
        }
        self.map(o, "additionalOperations", ptr, Self::operation);
    }

    fn operation(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::Operation, v, ptr) else { return };
        self.list(o, "parameters", ptr, PARAMETER);
        self.one(o, "requestBody", ptr, REQUEST_BODY);
        self.map(o, "responses", ptr, Self::response);
        if let Some(Value::Object(cbs)) = o.get("callbacks") {
            for (name, cb) in cbs {
                let base = format!("{ptr}/callbacks/{}", escape(name));
                if let Some(cb) = cb.as_object() {
                    for (expr, item) in cb {
                        self.path_item(item, &format!("{base}/{}", escape(expr)));
                    }
                }
            }
        }
    }

    fn header_like(&mut self, kind: Kind, v: &Value, ptr: &str) {
        let Some(o) = self.push(kind, v, ptr) else { return };
        self.one(o, "schema", ptr, Self::schema);
        self.map(o, "content", ptr, Self::media_type);
        self.map(o, "examples", ptr, EXAMPLE);
    }

    fn response(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::Response, v, ptr) else { return };
        self.map(o, "headers", ptr, HEADER);
        self.map(o, "content", ptr, Self::media_type);
    }

    fn media_type(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::MediaType, v, ptr) else { return };
        self.one(o, "schema", ptr, Self::schema);
        self.one(o, "itemSchema", ptr, Self::schema);
        self.map(o, "examples", ptr, EXAMPLE);
    }

    fn schema(&mut self, v: &Value, ptr: &str) {
        let Some(o) = self.push(Kind::Schema, v, ptr) else { return };
        for key in SCHEMA_MAPS {
            self.map(o, key, ptr, Self::schema);
        }
        for key in SCHEMA_ONE {
            self.one(o, key, ptr, Self::schema);
        }
        for key in SCHEMA_LISTS {
            self.list(o, key, ptr, Self::schema);
        }
    }
}

// The object kinds that need no walker method of their own.
type Visit = fn(&mut Walker, &Value, &str);
const PARAMETER: Visit = |w, v, p| w.header_like(Kind::Parameter, v, p);
const HEADER: Visit = |w, v, p| w.header_like(Kind::Header, v, p);
const EXAMPLE: Visit = |w, v, p| {
    w.push(Kind::Example, v, p);
};
const REQUEST_BODY: Visit = |w, v, p| {
    if let Some(o) = w.push(Kind::RequestBody, v, p) {
        w.map(o, "content", p, Walker::media_type);
    }
};

/// Schema keywords whose value is a map of name → subschema.
const SCHEMA_MAPS: [&str; 4] = ["properties", "patternProperties", "$defs", "dependentSchemas"];

/// Schema keywords whose value is one subschema. A boolean
/// `additionalProperties` is not an object and is skipped by `push`.
const SCHEMA_ONE: [&str; 11] = [
    "items",
    "additionalProperties",
    "not",
    "contentSchema",
    "if",
    "then",
    "else",
    "contains",
    "propertyNames",
    "unevaluatedItems",
    "unevaluatedProperties",
];

/// Schema keywords whose value is a list of subschemas.
const SCHEMA_LISTS: [&str; 4] = ["allOf", "anyOf", "oneOf", "prefixItems"];
