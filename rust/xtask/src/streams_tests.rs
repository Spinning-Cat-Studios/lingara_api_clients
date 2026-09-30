//! The spec half of 29.9.26p AC11 / 29.9.26ai AC9. It lives here, not in the
//! crate's `stream_tests.rs`, because it reads the view and the packaged
//! crate carries no `spec/`; `check-codegen-rust` then holds the committed
//! `ROUTES` to what `read` returns.

use serde_json::Value;

use super::read;

/// CONTRACT.md K5's rule, read straight off the view (ADR 29.9.26ai D2).
/// It walks the union's discriminator mapping, not the `oneOf` scan `ends`
/// uses, so the two reach each branch by different paths.
fn rule_outcome(view: &Value, entry: &Value, event: &str) -> &'static str {
    if entry["error"] == event {
        return "Raise";
    }
    let schemas = &view["components"]["schemas"];
    let union = entry["union"].as_str().unwrap();
    let branch = schemas[union]["discriminator"]["mapping"][event].as_str().unwrap().rsplit('/').next().unwrap();
    if schemas[branch]["properties"]["data"]["$ref"] == "#/components/schemas/Done" { "End" } else { "Yield" }
}

/// Each stream's `ends` names exactly its view entry's `endsOn`, in order,
/// each with D2's outcome.
#[test]
fn each_stream_ends_on_the_terminals_the_view_names() {
    let view = crate::read_json(&crate::workspace_root().join(crate::VIEW)).unwrap();
    let entries = view["x-lingara-streams"].as_array().unwrap();
    let streams = read(&view).unwrap();
    assert_eq!(streams.len(), entries.len());
    for (stream, entry) in streams.iter().zip(entries) {
        let op = &stream.operation_id;
        assert_eq!(entry["operationId"], op.as_str());
        let names: Vec<&str> = stream.ends.iter().map(|(e, _)| e.as_str()).collect();
        assert_eq!(serde_json::json!(names), entry["endsOn"], "{op}");
        for (event, outcome) in &stream.ends {
            assert_eq!(*outcome, rule_outcome(&view, entry, event), "{op} → {event}");
        }
    }
}
