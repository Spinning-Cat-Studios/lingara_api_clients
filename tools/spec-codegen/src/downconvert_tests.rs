use serde_json::{json, Value};

use crate::downconvert::{check_version, downconvert, refuse_32, KEYS_32};
use crate::lift::lift;
use crate::lift_tests::fixture;
use crate::walk::Kind;

/// One site of every kind `KEYS_32` names, and the pointer to each.
fn planting_ground() -> (Value, [(Kind, &'static str); 6]) {
    let doc = json!({
        "openapi": "3.1.0",
        "info": { "title": "t", "version": "1" },
        "tags": [{ "name": "x" }],
        "paths": {
            "/r": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": { "application/json": { "examples": { "e": { "value": 1 } } } },
                        },
                    },
                },
            },
        },
        "components": {},
    });
    let media = "/paths/~1r/get/responses/200/content/application~1json";
    let sites = [
        (Kind::Document, ""),
        (Kind::PathItem, "/paths/~1r"),
        (Kind::MediaType, media),
        (Kind::Example, "/paths/~1r/get/responses/200/content/application~1json/examples/e"),
        (Kind::Tag, "/tags/0"),
        (Kind::Components, "/components"),
    ];
    (doc, sites)
}

// 29.9.26m AC5
#[test]
fn downconvert_refuses_every_listed_32_key() {
    let mut doc = fixture();
    lift(&mut doc).unwrap();
    downconvert(&mut doc).unwrap();
    assert_eq!(doc["openapi"], "3.1.0");
    let text = doc.to_string();
    assert!(!text.contains("x-i18n") && !text.contains("oauth2MetadataUrl"), "{text}");
    assert_eq!(doc["components"]["securitySchemes"]["oauth2"]["type"], "oauth2");

    let (ground, sites) = planting_ground();
    refuse_32(&ground).unwrap();
    for (kind, key) in KEYS_32 {
        let (_, ptr) = sites.iter().find(|(k, _)| *k == kind).unwrap();
        let mut doc = ground.clone();
        doc.pointer_mut(ptr).unwrap()[key] = json!({});
        let err = refuse_32(&doc).unwrap_err();
        assert_eq!(err.0, format!("{ptr}/{key}: a 3.2-only key"), "{key}");
    }
}

#[test]
fn a_querystring_parameter_is_refused() {
    let (mut doc, _) = planting_ground();
    doc["paths"]["/r"]["get"]["parameters"] = json!([{ "name": "q", "in": "querystring" }]);
    assert_eq!(
        refuse_32(&doc).unwrap_err().0,
        "/paths/~1r/get/parameters/0/in: querystring is a 3.2-only location"
    );
}

#[test]
fn only_a_32_document_is_read() {
    assert_eq!(check_version(&json!({ "openapi": "3.2.0" })).unwrap(), "3.2.0");
    let err = check_version(&json!({ "openapi": "3.1.0" })).unwrap_err();
    assert_eq!(err.0, "/openapi: Some(\"3.1.0\") is not a 3.2 document");
}
