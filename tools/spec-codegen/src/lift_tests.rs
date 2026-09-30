use serde_json::{json, Value};

use crate::lift::lift;

fn branch(event: &str, data: &str) -> Value {
    json!({
        "type": "object",
        "required": ["event", "data"],
        "properties": {
            "event": { "const": event },
            "data": {
                "contentMediaType": "application/json",
                "contentSchema": { "$ref": format!("#/components/schemas/{data}") },
            },
        },
    })
}

/// `doThing`'s `text/event-stream` media object: three events, and the
/// `x-lingara-stream` ADR 29.9.26ai D1 requires.
fn stream_media() -> Value {
    json!({
        "itemSchema": { "oneOf": [branch("started", "Started"), branch("item_done", "Item"), branch("error", "Err")] },
        "example": "event: started\ndata: {}\n\n",
        "x-lingara-stream": { "ends_on": ["item_done", "error"], "error": "error", "keepalive_seconds": 15, "resumable": false },
    })
}

/// A 3.2 document with one streamed operation (`doThing`, three events, and
/// the `x-lingara-stream` ADR 29.9.26ai D1 requires) and one plain one
/// (`getR`). Shared by the other test modules.
pub(crate) fn fixture() -> Value {
    json!({
        "openapi": "3.2.0",
        "info": { "title": "t", "version": "1", "x-i18n": "info" },
        "paths": {
            "/s": {
                "parameters": [{ "$ref": "#/components/parameters/V" }],
                "post": {
                    "operationId": "doThing",
                    "x-i18n": "ops.do",
                    "security": [{ "oauth2": ["a:write", "a:read"] }],
                    "requestBody": {
                        "content": { "application/json": { "schema": { "$ref": "#/components/schemas/Req" } } },
                    },
                    "responses": {
                        "200": {
                            "description": "events",
                            "content": { "text/event-stream": stream_media() },
                        },
                    },
                },
            },
            "/r": { "get": { "operationId": "getR", "responses": { "200": { "description": "ok" } } } },
        },
        "components": {
            "schemas": {
                "Req": { "type": "object", "x-i18n": "schemas.req" },
                "Started": { "type": "object" },
                "Item": { "type": "object" },
                "Err": { "type": "object" },
            },
            "parameters": { "V": { "name": "v", "in": "header", "schema": { "type": "string" } } },
            "securitySchemes": {
                "oauth2": {
                    "type": "oauth2",
                    "oauth2MetadataUrl": "https://example.invalid/.well-known/oauth-authorization-server",
                    "flows": { "clientCredentials": { "tokenUrl": "https://example.invalid/token", "scopes": {} } },
                },
            },
        },
    })
}

const BRANCHES: &str = "/paths/~1s/post/responses/200/content/text~1event-stream/itemSchema/oneOf";

// 29.9.26m AC1
#[test]
fn each_branch_becomes_a_named_schema_under_a_discriminated_union() {
    let mut doc = fixture();
    lift(&mut doc).unwrap();
    let schemas = &doc["components"]["schemas"];
    assert_eq!(
        schemas["DoThingEventStarted"],
        json!({
            "type": "object",
            "required": ["event", "data"],
            "properties": {
                "event": { "type": "string", "enum": ["started"] },
                "data": { "$ref": "#/components/schemas/Started" },
            },
        })
    );
    assert_eq!(schemas["DoThingEventItemDone"]["properties"]["event"]["enum"], json!(["item_done"]));
    assert_eq!(
        schemas["DoThingEvent"],
        json!({
            "oneOf": [
                { "$ref": "#/components/schemas/DoThingEventStarted" },
                { "$ref": "#/components/schemas/DoThingEventItemDone" },
                { "$ref": "#/components/schemas/DoThingEventError" },
            ],
            "discriminator": {
                "propertyName": "event",
                "mapping": {
                    "started": "#/components/schemas/DoThingEventStarted",
                    "item_done": "#/components/schemas/DoThingEventItemDone",
                    "error": "#/components/schemas/DoThingEventError",
                },
            },
        })
    );
}

// 29.9.26m AC2
#[test]
fn an_unrecognised_branch_is_refused_by_name() {
    let mut doc = fixture();
    doc.pointer_mut(&format!("{BRANCHES}/1/properties/event")).unwrap()["description"] = json!("x");
    let err = lift(&mut doc).unwrap_err();
    assert_eq!(
        err.0,
        "operation doThing: itemSchema branch 1 is not the event-const + contentSchema-$ref shape"
    );

    let mut doc = fixture();
    doc.pointer_mut(&format!("{BRANCHES}/0/properties/data")).unwrap()["contentMediaType"] = json!("text/plain");
    assert!(lift(&mut doc).unwrap_err().0.contains("branch 0"));
}

// 29.9.26m AC3
#[test]
fn a_name_collision_is_refused() {
    let mut doc = fixture();
    doc["components"]["schemas"]["DoThingEventStarted"] = json!({ "type": "string" });
    let err = lift(&mut doc).unwrap_err();
    assert_eq!(err.0, "lifted schema DoThingEventStarted collides with an existing component schema");
}

// 29.9.26m AC4
#[test]
fn a_stripped_stream_is_recorded_not_lost() {
    let mut doc = fixture();
    lift(&mut doc).unwrap();
    let paths = doc["paths"].as_object().unwrap();
    assert!(!paths.contains_key("/s"), "an emptied path item is dropped, path-level parameters notwithstanding");
    assert!(paths.contains_key("/r"));
    assert_eq!(
        doc["x-lingara-streams"],
        json!([{
            "operationId": "doThing",
            "method": "post",
            "path": "/s",
            "requestBody": "#/components/schemas/Req",
            "parameters": ["#/components/parameters/V"],
            "scopes": ["a:write", "a:read"],
            "union": "DoThingEvent",
            "events": ["started", "item_done", "error"],
            "endsOn": ["item_done", "error"],
            "error": "error",
            "keepaliveSeconds": 15,
            "resumable": false,
        }])
    );
}

#[test]
fn a_path_item_keeping_another_operation_is_kept() {
    let mut doc = fixture();
    doc["paths"]["/s"]["get"] = json!({ "operationId": "getS", "responses": {} });
    lift(&mut doc).unwrap();
    let item = doc["paths"]["/s"].as_object().unwrap();
    assert!(item.contains_key("get") && !item.contains_key("post"));
}

#[test]
fn a_repeated_event_and_an_unnameable_event_are_refused() {
    let mut doc = fixture();
    doc.pointer_mut(&format!("{BRANCHES}/1/properties/event")).unwrap()["const"] = json!("started");
    assert_eq!(lift(&mut doc).unwrap_err().0, "operation doThing: event \"started\" appears twice");

    let mut doc = fixture();
    doc.pointer_mut(&format!("{BRANCHES}/1/properties/event")).unwrap()["const"] = json!("a b");
    assert!(lift(&mut doc).unwrap_err().0.contains("cannot name a schema"));
}
