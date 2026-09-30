use serde_json::json;

use crate::dialect30::refuse_31;
use crate::downconvert::refuse_32;
use crate::walk::{sites, Kind, Site};

// 29.9.26m AC7
#[test]
fn property_names_and_example_values_are_not_keywords() {
    let doc = json!({
        "openapi": "3.0.3",
        "info": { "title": "t", "version": "1" },
        "paths": {
            "/r": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": {
                                "application/json": {
                                    "schema": { "$ref": "#/components/schemas/P" },
                                    "example": { "itemSchema": {}, "const": 1, "kind": "x" },
                                    "examples": { "e": { "value": { "itemSchema": {}, "$self": "x" } } },
                                },
                            },
                        },
                    },
                },
            },
        },
        "components": {
            "schemas": {
                "P": {
                    "type": "object",
                    "properties": {
                        "const": { "type": "string" },
                        "kind": { "type": "string" },
                        "itemSchema": { "type": "string" },
                        "examples": { "type": "string" },
                    },
                    "example": { "const": "a", "prefixItems": [] },
                },
            },
        },
    });
    refuse_32(&doc).unwrap();
    refuse_31(&doc).unwrap();
}

#[test]
fn sites_escape_pointers_and_skip_example_values() {
    let doc = json!({
        "paths": { "/v1/a~b": { "get": { "responses": { "200": { "content": {
            "application/json": { "schema": { "type": "string" }, "example": { "type": "object" } },
        } } } } } },
    });
    let got = sites(&doc);
    let media = "/paths/~1v1~1a~0b/get/responses/200/content/application~1json";
    assert!(got.contains(&Site { kind: Kind::MediaType, pointer: media.into() }));
    assert!(got.contains(&Site { kind: Kind::Schema, pointer: format!("{media}/schema") }));
    assert!(!got.iter().any(|s| s.pointer.contains("example")), "{got:?}");
}
