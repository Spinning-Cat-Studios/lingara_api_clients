use std::fs;
use std::path::PathBuf;

use serde_json::{json, Value};

use crate::cli::{current_pair, OUTPUTS};
use crate::{build_view, render};

fn spec_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../spec")
}

fn read(path: &std::path::Path) -> Value {
    serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap()
}

/// The vendored generator input: the current version's frozen snapshot
/// pair, not the live `spec/openapi.json` (ADR 30.9.26a §3, 30.9.26aa D1).
fn vendored_pair() -> (Value, Option<Value>) {
    let pair = current_pair(&spec_dir().join("versions.toml")).unwrap();
    (read(&pair.openapi), pair.asyncapi.as_deref().map(read))
}

const UNIONS: [&str; 6] = [
    "GenerateVocabularyEvent",
    "CreateLessonPlanEvent",
    "StreamLessonPlanEvent",
    "SendTutorMessageEvent",
    "StreamEventsEvent",
    "SendDialogueTurnEvent",
];
const REMAINING: [&str; 10] = [
    "getLessonPlan",
    "getUsage",
    "getOpenApiDocument",
    "getAsyncApiDocument",
    "listApiVersions",
    "getApiVersion",
    "listEvents",
    "sendEvent",
    "createEmbedToken",
    "deleteEmbedPlayer",
];

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
    let (spec, catalogue) = vendored_pair();
    build_view(&spec, catalogue.as_ref(), "backend@test").unwrap()
}

// 29.9.26ai AC4
#[test]
fn the_vendored_spec_names_how_each_stream_ends() {
    let want = json!([
        ["generateVocabulary", ["done", "error"], 15, false],
        ["createLessonPlan", ["result", "error"], 15, false],
        ["streamLessonPlan", ["result", "pending", "error"], 15, false],
        ["sendTutorMessage", ["done", "error"], null, false],
        ["streamEvents", ["done", "error"], 15, true],
        ["sendDialogueTurn", ["done", "error"], null, false],
    ]);
    let view = vendored_view();
    for (name, doc) in OUTPUTS.iter().zip([&view.v31, &view.v30]) {
        let entries = doc["x-lingara-streams"].as_array().unwrap();
        let got: Vec<Value> = entries
            .iter()
            .map(|e| json!([e["operationId"], e["endsOn"], e["keepaliveSeconds"], e["resumable"]]))
            .collect();
        assert_eq!(Value::Array(got), want, "{name}");
        assert!(entries.iter().all(|e| e["error"] == "error"), "{name}");
    }
}

// 29.9.26m AC8; 1.10.26w adds the embed mint's stream and two operations
#[test]
fn the_vendored_spec_yields_six_streams_and_ten_operations() {
    let (spec, catalogue) = vendored_pair();
    let source = fs::read_to_string(spec_dir().join("SOURCE")).unwrap();
    let first = build_view(&spec, catalogue.as_ref(), source.trim()).unwrap();
    let second = build_view(&spec, catalogue.as_ref(), source.trim()).unwrap();

    let mut want_unions = UNIONS.to_vec();
    want_unions.sort_unstable();
    let mut want_ops = REMAINING.to_vec();
    want_ops.sort_unstable();
    for (name, (a, b)) in OUTPUTS.iter().zip([(&first.v31, &second.v31), (&first.v30, &second.v30)]) {
        let rendered = render(a);
        assert_eq!(rendered, render(b), "{name}: two runs differ");
        assert_eq!(unions(a), want_unions, "{name}");
        assert_eq!(operation_ids(a), want_ops, "{name}");
        assert_eq!(a["x-lingara-streams"].as_array().unwrap().len(), 6, "{name}");
        assert_eq!(a["info"]["x-lingara-view"]["source"], source.trim(), "{name}");
        assert!(!rendered.contains("x-i18n") && !rendered.contains("itemSchema"), "{name}");
        let committed = fs::read_to_string(spec_dir().join("generator").join(name)).unwrap();
        assert!(committed == rendered, "{name}: the committed dialect is stale; run make spec-view");
    }
}

// 30.9.26aa AC12
#[test]
fn the_vendored_pair_names_the_event_catalogue() {
    let view = vendored_view();
    for (name, doc) in OUTPUTS.iter().zip([&view.v31, &view.v30]) {
        let got: Vec<Value> = doc["x-lingara-events"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| json!([e["type"], e["direction"]]))
            .collect();
        // The six of D3 as written, plus A2's two `app.*` lifecycle events
        // (Step 0: roadmap 30.9.26ae landed them before E6's mint), the apps
        // pause's two (7.10.26b) and B2's practice pair (1.10.26w Step 0).
        let want = json!([
            ["lesson_plan.ready", "out"],
            ["lesson_plan.failed", "out"],
            ["usage.threshold_reached", "out"],
            ["webhook.test", "out"],
            ["app.installed", "out"],
            ["app.uninstalled", "out"],
            ["app.disabled", "out"],
            ["app.enabled", "out"],
            ["world.context_changed", "in"],
            ["world.practice_requested", "in"],
            ["world.practice_completed", "in"],
            ["practice.completed", "out"],
        ]);
        assert_eq!(Value::Array(got), want, "{name}");
        let events = doc["x-lingara-events"].as_array().unwrap();
        let test = events.iter().find(|e| e["type"] == "webhook.test").expect("webhook.test is lifted");
        assert_eq!(test["transports"], json!(["webhook"]), "{name}: webhook.test is webhook-only");
        let tail = doc["x-lingara-streams"].as_array().unwrap().iter().find(|e| e["operationId"] == "streamEvents");
        let tail = tail.expect("the events stream is lifted");
        assert_eq!(tail["resumable"], true, "{name}: the stream is a tail");
        assert_eq!(tail["endsOn"], json!(["done", "error"]), "{name}");
        assert!(doc["components"]["schemas"].get("InboundEventRequest").is_none(), "{name}");
    }
}

// 1.10.26w AC2
#[test]
fn the_vendored_pair_names_the_embed_operations() {
    let view = vendored_view();
    for (name, doc) in OUTPUTS.iter().zip([&view.v31, &view.v30]) {
        let ops = operation_ids(doc);
        assert!(ops.contains(&"createEmbedToken") && ops.contains(&"deleteEmbedPlayer"), "{name}: {ops:?}");
        let streams = doc["x-lingara-streams"].as_array().unwrap();
        let turn = streams.iter().find(|e| e["operationId"] == "sendDialogueTurn").expect("the NPC turn is lifted");
        assert_eq!(turn["endsOn"], json!(["done", "error"]), "{name}");
        assert_eq!(turn["keepaliveSeconds"], Value::Null, "{name}: no keepalive");
        assert_eq!(turn["resumable"], false, "{name}: not a tail");
        let events = doc["x-lingara-events"].as_array().unwrap();
        let direction = |t: &str| events.iter().find(|e| e["type"] == t).map(|e| e["direction"].clone());
        assert_eq!(direction("world.practice_completed"), Some(json!("in")), "{name}");
        assert_eq!(direction("practice.completed"), Some(json!("out")), "{name}");
    }
}
