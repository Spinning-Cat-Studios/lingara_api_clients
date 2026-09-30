use std::fs;
use std::path::PathBuf;

use serde_json::Value;

use crate::cli::{current_snapshot, OUTPUTS};
use crate::{build_view, render};

fn spec_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../spec")
}

/// The vendored generator input: the current version's frozen snapshot, not
/// the live `spec/openapi.json` (ADR 30.9.26a §3).
fn vendored_spec() -> Value {
    let input = current_snapshot(&spec_dir().join("versions.toml")).unwrap();
    serde_json::from_str(&fs::read_to_string(input).unwrap()).unwrap()
}

const UNIONS: [&str; 4] = ["GenerateVocabularyEvent", "CreateLessonPlanEvent", "StreamLessonPlanEvent", "SendTutorMessageEvent"];
const REMAINING: [&str; 5] = ["getLessonPlan", "getUsage", "getOpenApiDocument", "listApiVersions", "getApiVersion"];

fn operation_ids(doc: &Value) -> Vec<&str> {
    let paths = doc["paths"].as_object().unwrap();
    let ops = paths.values().flat_map(|item| item.as_object().unwrap().values());
    let mut ids: Vec<&str> = ops.filter_map(|op| op.get("operationId")?.as_str()).collect();
    ids.sort_unstable();
    ids
}

fn unions(doc: &Value) -> Vec<&str> {
    let schemas = doc["components"]["schemas"].as_object().unwrap();
    let mut names: Vec<&str> =
        schemas.iter().filter(|(_, s)| s.get("discriminator").is_some()).map(|(n, _)| n.as_str()).collect();
    names.sort_unstable();
    names
}

fn vendored_view() -> crate::View {
    build_view(&vendored_spec(), "backend@test").unwrap()
}

// 29.9.26ai AC4
#[test]
fn the_vendored_spec_names_how_each_stream_ends() {
    let want = serde_json::json!([
        ["generateVocabulary", ["done", "error"], 15],
        ["createLessonPlan", ["result", "error"], 15],
        ["streamLessonPlan", ["result", "pending", "error"], 15],
        ["sendTutorMessage", ["done", "error"], null],
    ]);
    let view = vendored_view();
    for (name, doc) in OUTPUTS.iter().zip([&view.v31, &view.v30]) {
        let entries = doc["x-lingara-streams"].as_array().unwrap();
        let got: Vec<Value> = entries
            .iter()
            .map(|e| serde_json::json!([e["operationId"], e["endsOn"], e["keepaliveSeconds"]]))
            .collect();
        assert_eq!(Value::Array(got), want, "{name}");
        assert!(entries.iter().all(|e| e["error"] == "error" && e["resumable"] == false), "{name}");
    }
}

// 29.9.26m AC8
#[test]
fn the_vendored_spec_yields_four_streams_and_five_operations() {
    let spec = vendored_spec();
    let source = fs::read_to_string(spec_dir().join("SOURCE")).unwrap();
    let first = build_view(&spec, source.trim()).unwrap();
    let second = build_view(&spec, source.trim()).unwrap();

    let mut want_unions = UNIONS.to_vec();
    want_unions.sort_unstable();
    let mut want_ops = REMAINING.to_vec();
    want_ops.sort_unstable();
    for (name, (a, b)) in OUTPUTS.iter().zip([(&first.v31, &second.v31), (&first.v30, &second.v30)]) {
        let rendered = render(a);
        assert_eq!(rendered, render(b), "{name}: two runs differ");
        assert_eq!(unions(a), want_unions, "{name}");
        assert_eq!(operation_ids(a), want_ops, "{name}");
        assert_eq!(a["x-lingara-streams"].as_array().unwrap().len(), 4, "{name}");
        assert_eq!(a["info"]["x-lingara-view"]["source"], source.trim(), "{name}");
        assert!(!rendered.contains("x-i18n") && !rendered.contains("itemSchema"), "{name}");
        let committed = fs::read_to_string(spec_dir().join("generator").join(name)).unwrap();
        assert!(committed == rendered, "{name}: the committed dialect is stale; run make spec-view");
    }
}
