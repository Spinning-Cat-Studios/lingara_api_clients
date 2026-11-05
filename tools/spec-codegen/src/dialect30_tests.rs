use serde_json::{json, Value};

use crate::dialect30::{dialect30, refuse_31, FIELDS_31, SCHEMA_KEYS_31};
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


    for key in SCHEMA_KEYS_31 {
        let mut doc = view31();
        doc["components"]["schemas"]["B"][key] = json!([]);
        if key == "const" {
            // 5.11.26k: a `const` is now rewritten, not refused. Beside `B`'s
            // `type: object` nothing satisfies `[]` in either dialect, so the
            // rewrite is still faithful.
            assert_eq!(dialect30(&doc).unwrap()["components"]["schemas"]["B"]["enum"], json!([[]]));
            continue;
        }
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

// A `$ref` with siblings (28.10.26d's `pedagogy`) is said through `allOf`;
// one already beside an `allOf` has no single spelling and is refused.
#[test]
fn a_ref_with_siblings_moves_into_all_of() {
    let mut doc = view31();
    let ref_b = json!({ "$ref": "#/components/schemas/B", "description": "x", "default": "subtle" });
    doc["components"]["schemas"]["A"]["properties"]["s"] = ref_b;
    let out = dialect30(&doc).unwrap();
    assert_eq!(
        out["components"]["schemas"]["A"]["properties"]["s"],
        json!({ "description": "x", "default": "subtle", "allOf": [{ "$ref": "#/components/schemas/B" }] })
    );

    doc["components"]["schemas"]["A"]["properties"]["s"]["allOf"] = json!([{ "type": "object" }]);
    assert_eq!(refusal(&doc), "/components/schemas/A/properties/s/allOf: a $ref beside an allOf");
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

fn with_c(schema: Value) -> Value {
    let mut doc = view31();
    doc["components"]["schemas"]["C"] = schema;
    doc
}

// 5.11.26k AC1
#[test]
fn a_const_becomes_a_one_value_enum() {
    let doc = with_c(json!({ "type": "string", "const": "planned" }));
    let before = doc.clone();
    let out = dialect30(&doc).unwrap();
    assert_eq!(out["components"]["schemas"]["C"], json!({ "type": "string", "enum": ["planned"] }));
    assert_eq!(doc, before, "the 3.1 input is untouched");

    let out = dialect30(&with_c(json!({ "const": [] }))).unwrap();
    assert_eq!(out["components"]["schemas"]["C"], json!({ "enum": [[]] }));
}

// 5.11.26k AC2
#[test]
fn a_const_beside_a_type_array_takes_both_rewrites() {
    let out = dialect30(&with_c(json!({ "type": ["string", "null"], "const": "kept" }))).unwrap();
    assert_eq!(out["components"]["schemas"]["C"], json!({ "type": "string", "nullable": true, "enum": ["kept"] }));
}

// 5.11.26k AC3, AC4
#[test]
fn a_const_with_no_exact_30_form_is_refused() {
    assert_eq!(
        refusal(&with_c(json!({ "type": "string", "const": null }))),
        "/components/schemas/C/const: a null const has no 3.0 form"
    );
    assert_eq!(
        refusal(&with_c(json!({ "type": "string", "const": "a", "enum": ["a", "b"] }))),
        "/components/schemas/C/const: a const beside an enum"
    );
}

// 5.11.26k AC5
#[test]
fn the_plan_answer_arms_pass_the_30_dialect() {
    let doc = with_c(json!({
        "oneOf": [
            {
                "type": "object",
                "properties": { "fragment": { "$ref": "#/components/schemas/B" }, "status": { "type": "string", "const": "planned" } },
                "required": ["status", "fragment"],
            },
            {
                "type": "object",
                "properties": { "reason": { "$ref": "#/components/schemas/B" }, "status": { "type": "string", "const": "kept" } },
                "required": ["status", "reason"],
            },
        ],
    }));
    let out = dialect30(&doc).unwrap();
    let arms = &out["components"]["schemas"]["C"]["oneOf"];
    assert_eq!(arms[0]["properties"]["status"], json!({ "type": "string", "enum": ["planned"] }));
    assert_eq!(arms[1]["properties"]["status"], json!({ "type": "string", "enum": ["kept"] }));
}

// 5.11.26k AC8
#[test]
fn refuse_31_still_refuses_a_left_over_const() {
    // A bare document: `view31`'s own 3.1 forms would be refused first.
    let doc = json!({
        "openapi": "3.0.3",
        "info": { "title": "t", "version": "1" },
        "paths": {},
        "components": { "schemas": { "C": { "type": "string", "const": "planned" } } },
    });
    assert_eq!(refuse_31(&doc).unwrap_err().0, "/components/schemas/C/const: a 3.1-only schema keyword");
}

// 30.9.26aa AC10
#[test]
fn webhooks_is_refused_beside_the_event_catalogue() {
    let mut doc = view31();
    doc["x-lingara-events"] = json!([
        { "type": "lesson_plan.ready", "direction": "out", "arm": "LessonPlanReady", "data": "#/components/schemas/B", "transports": ["webhook"] },
    ]);
    dialect30(&doc).expect("the event catalogue alone is a 3.0 view");
    doc["webhooks"] = json!({});
    assert_eq!(refusal(&doc), "/webhooks: a 3.1-only field", "a filled-in webhooks map still fails loudly");
}
