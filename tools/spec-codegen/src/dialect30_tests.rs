use serde_json::{json, Value};

use crate::dialect30::{dialect30, FIELDS_31, SCHEMA_KEYS_31};
use crate::walk::Kind;

fn view31() -> Value {
    json!({
        "openapi": "3.1.0",
        "info": { "title": "t", "version": "1", "license": { "name": "MIT" } },
        "paths": {},
        "components": {
            "schemas": {
                "A": {
                    "type": "object",
                    "properties": {
                        "n": { "type": ["string", "null"], "format": "date-time" },
                        "r": { "description": "x", "anyOf": [{ "$ref": "#/components/schemas/B" }, { "type": "null" }] },
                        "e": { "type": "string", "enum": ["started"] },
                    },
                },
                "B": { "type": "object" },
                "NoType": { "oneOf": [] },
            },
        },
        "x-lingara-streams": [],
    })
}

fn refusal(doc: &Value) -> String {
    dialect30(doc).unwrap_err().0
}

// 29.9.26m AC6
#[test]
fn the_30_dialect_rewrites_nullables_and_refuses_the_rest() {
    let out = dialect30(&view31()).unwrap();
    assert_eq!(out["openapi"], "3.0.3");
    let props = &out["components"]["schemas"]["A"]["properties"];
    assert_eq!(props["n"], json!({ "type": "string", "nullable": true, "format": "date-time" }));
    assert_eq!(
        props["r"],
        json!({ "description": "x", "type": "object", "nullable": true, "allOf": [{ "$ref": "#/components/schemas/B" }] })
    );
    assert_eq!(props["e"], json!({ "type": "string", "enum": ["started"] }));
    assert_eq!(out["x-lingara-streams"], json!([]));

    let mut doc = view31();
    doc["components"]["schemas"]["A"]["properties"]["s"] = json!({ "$ref": "#/components/schemas/B", "description": "x" });
    assert_eq!(
        refusal(&doc),
        "/components/schemas/A/properties/s/$ref: 3.0 ignores a $ref's sibling keywords"
    );

    for key in SCHEMA_KEYS_31 {
        let mut doc = view31();
        doc["components"]["schemas"]["B"][key] = json!([]);
        assert_eq!(refusal(&doc), format!("/components/schemas/B/{key}: a 3.1-only schema keyword"));
    }
    for (kind, key) in FIELDS_31 {
        let ptr = match kind {
            Kind::Document => "",
            Kind::Components => "/components",
            Kind::Info => "/info",
            Kind::License => "/info/license",
            other => panic!("no planting site for {other:?}"),
        };
        let mut doc = view31();
        doc.pointer_mut(ptr).unwrap()[key] = json!({});
        assert_eq!(refusal(&doc), format!("{ptr}/{key}: a 3.1-only field"));
    }
}

#[test]
fn types_and_bounds_with_no_30_form_are_refused() {
    let set = |schema: Value| {
        let mut doc = view31();
        doc["components"]["schemas"]["C"] = schema;
        refusal(&doc)
    };
    assert!(set(json!({ "type": ["string", "integer"] })).starts_with("/components/schemas/C/type:"));
    assert!(set(json!({ "type": "null" })).contains("a lone null type"));
    assert_eq!(
        set(json!({ "type": "integer", "exclusiveMinimum": 0 })),
        "/components/schemas/C/exclusiveMinimum: a numeric exclusive bound is 3.1-only"
    );

    let mut doc = view31();
    doc["components"]["schemas"]["A"]["properties"]["r"]["anyOf"][0]["$ref"] = json!("#/components/schemas/NoType");
    assert!(refusal(&doc).contains("has no single type for nullable to sit beside"));
}
