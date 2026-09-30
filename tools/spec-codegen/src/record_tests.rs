use serde_json::{json, Value};

use crate::lift::lift;
use crate::lift_tests::fixture;

const MEDIA: &str = "/paths/~1s/post/responses/200/content/text~1event-stream";

fn with_extension(ext: Value) -> Value {
    let mut doc = fixture();
    doc.pointer_mut(MEDIA).unwrap()["x-lingara-stream"] = ext;
    doc
}

fn lifted_entry(doc: &mut Value) -> Value {
    lift(doc).unwrap();
    doc["x-lingara-streams"][0].clone()
}

fn refusal(doc: &mut Value) -> String {
    lift(doc).unwrap_err().0
}

// 29.9.26ai AC1
#[test]
fn the_stream_extension_is_copied_in_camel_case() {
    let entry = lifted_entry(&mut fixture());
    let keys: Vec<&str> = entry.as_object().unwrap().keys().map(String::as_str).collect();
    assert_eq!(keys[7..], ["events", "endsOn", "error", "keepaliveSeconds", "resumable"]);
    assert_eq!(entry["endsOn"], json!(["item_done", "error"]));
    assert_eq!(entry["error"], "error");
    assert_eq!(entry["keepaliveSeconds"], 15);
    assert_eq!(entry["resumable"], false);

    let mut doc = with_extension(json!({ "ends_on": ["error"], "error": "error", "resumable": false }));
    let entry = lifted_entry(&mut doc);
    assert!(entry.as_object().unwrap().contains_key("keepaliveSeconds"), "written as null, never left out");
    assert_eq!(entry["keepaliveSeconds"], Value::Null);
}

// 29.9.26ai AC2
#[test]
fn a_stream_without_its_extension_is_refused() {
    let mut doc = fixture();
    doc.pointer_mut(MEDIA).unwrap().as_object_mut().unwrap().shift_remove("x-lingara-stream");
    assert_eq!(refusal(&mut doc), "operation doThing: the stream has no x-lingara-stream");
}

fn valid() -> Value {
    json!({ "ends_on": ["item_done", "error"], "error": "error", "keepalive_seconds": 15, "resumable": false })
}

fn malformed(key: &str, value: Value) -> String {
    let mut ext = valid();
    ext[key] = value;
    refusal(&mut with_extension(ext))
}

// 29.9.26ai AC3
#[test]
fn a_malformed_extension_is_refused() {
    assert!(malformed("ends_on", json!([])).ends_with("ends_on is empty"));
    assert!(malformed("ends_on", json!(["gone", "error"])).contains("ends_on names \"gone\""));
    assert!(malformed("error", json!(1)).contains("error is not an event name"));
    assert!(malformed("ends_on", json!(["item_done"])).contains("error \"error\" is not in ends_on"));
    for bad in [json!(0), json!(-1), json!(1.5), json!("15")] {
        assert!(malformed("keepalive_seconds", bad).contains("keepalive_seconds is not a positive integer"));
    }
    assert!(malformed("resumable", json!("no")).contains("resumable is not a boolean"));
    assert!(malformed("retry_ms", json!(1)).contains("unknown key \"retry_ms\""));
    assert!(refusal(&mut with_extension(json!("x"))).contains("is not an object"));

    // A `Done` ending payload must be exactly `{type: object}`.
    let mut doc = fixture();
    let item_done_ref = format!("{MEDIA}/itemSchema/oneOf/1/properties/data/contentSchema/$ref");
    *doc.pointer_mut(&item_done_ref).unwrap() = json!("#/components/schemas/Done");
    doc["components"]["schemas"]["Done"] = json!({ "type": "object" });
    lift(&mut doc.clone()).unwrap();
    doc["components"]["schemas"]["Done"] = json!({ "type": "object", "additionalProperties": true });
    assert!(refusal(&mut doc).contains("payload is Done, which is not exactly {type: object}"));
}
