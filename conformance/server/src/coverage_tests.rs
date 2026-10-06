use std::path::{Path, PathBuf};

use serde_json::{Value, json};

use crate::case::{self, Loaded};
use crate::coverage::{check, operations, terminal_problems};
use crate::test_support::loaded;

fn repo() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn read(rel: &str) -> Value {
    serde_json::from_str(&std::fs::read_to_string(repo().join(rel)).unwrap()).unwrap()
}

/// `paths` carrying one GET per operation.
fn paths(ops: &[&str]) -> Value {
    let mut paths = serde_json::Map::new();
    for (i, op) in ops.iter().enumerate() {
        paths.insert(format!("/p{i}"), json!({ "get": { "operationId": op } }));
    }
    Value::Object(paths)
}

/// A view generating exactly `ops`, all of them plain (non-streaming).
fn view_with(ops: &[&str]) -> Value {
    json!({ "paths": paths(ops), "x-lingara-streams": [] })
}

fn view() -> Value {
    json!({ "x-lingara-streams": [
        stream("generateVocabulary", &["started", "item", "done", "error"], &["done", "error"], json!(15)),
        stream("createLessonPlan", &["started", "phase", "result", "error"], &["result", "error"], json!(15)),
        stream("streamLessonPlan", &["started", "phase", "result", "pending", "error"], &["result", "pending", "error"], json!(15)),
        stream("sendTutorMessage", &["delta", "notice", "done", "error"], &["done", "error"], Value::Null),
    ]})
}

fn stream(op: &str, events: &[&str], ends_on: &[&str], keepalive: Value) -> Value {
    json!({
        "operationId": op, "events": events, "endsOn": ends_on, "error": "error",
        "keepaliveSeconds": keepalive, "resumable": false,
    })
}

/// One case calling `op`, listing every behaviour unless `behaviours` says.
fn calling(op: &str, behaviours: &str) -> Loaded {
    let yaml = format!(
        "id: op.{op}\ntitle: t\nbehaviours: {behaviours}\nsteps:\n  - call: {{ operation: {op} }}\n    expect: {{ outcome: completed }}\n"
    );
    loaded(&format!("op/{op}.yaml"), &yaml)
}

const ALL: &str = "[K1, K2, K3, K4, K5, K5a, K6]";

/// 29.9.26n AC2: every case file parses with `deny_unknown_fields`, and its
/// id is `<group>.<slug>` of its path.
#[test]
fn every_case_file_parses_and_matches_its_path() {
    let (cases, errors) = case::load_dir(&repo().join("conformance/cases"));
    assert!(errors.is_empty(), "{errors:#?}");
    assert!(!cases.is_empty());
    for loaded in &cases {
        let group = loaded.case.id.split('.').next().unwrap();
        assert!(case::GROUPS.contains(&group), "{}", loaded.case.id);
    }
    let wrong = "id: k1.other\ntitle: t\nbehaviours: [K1]\nsteps:\n  - advance_clock_s: 1\n";
    let refused = case::parse(wrong, Path::new("k1/this.yaml")).unwrap_err();
    assert!(refused.contains("should be `k1.this`"), "{refused}");
    assert!(case::parse(wrong, Path::new("k9/other.yaml")).is_err(), "k9 is not a group");
}

/// 29.9.26n AC3: an operation in the view with no case fails the guard.
#[test]
fn an_operation_without_a_case_fails() {
    let problems = check(&view_with(&["getUsage", "listApiVersions"]), &[calling("getUsage", ALL)]);
    assert_eq!(problems, vec!["operation `listApiVersions` has no case"]);
}

/// 29.9.26n AC4: a case naming an operation the view lacks fails the guard.
#[test]
fn a_stale_operation_fails() {
    let cases = [calling("getUsage", ALL), calling("getUsageOld", ALL)];
    let problems = check(&view_with(&["getUsage"]), &cases);
    assert_eq!(problems, vec!["op.getUsageOld: `getUsageOld` is not an operation in the view"]);
}

/// An operation only the development bundle carries (as the `/v1/embed`
/// routes did before a frozen version brought them into the view) is in no
/// library, so it needs no case. The guard never reads the bundle.
#[test]
fn an_operation_outside_the_view_needs_no_case() {
    let view = view_with(&["getUsage"]); // the bundle would add createEmbedToken
    let problems = check(&view, &[calling("getUsage", ALL)]);
    assert!(problems.is_empty(), "{problems:#?}");
    assert!(!operations(&view).contains("createEmbedToken"));
}

/// A streaming operation is in the view's `x-lingara-streams`, not its
/// `paths`, and still needs a case.
#[test]
fn a_streaming_operation_needs_a_case() {
    let problems = check(&view(), &[calling("generateVocabulary", ALL), calling("createLessonPlan", ALL), calling("streamLessonPlan", ALL)]);
    assert_eq!(problems, vec!["operation `sendTutorMessage` has no case"]);
}

/// 29.9.26n AC5: a behaviour no case lists fails the guard.
#[test]
fn a_behaviour_without_a_case_fails() {
    let problems = check(&view_with(&["getUsage"]), &[calling("getUsage", "[K1, K2, K3, K5, K5a, K6]")]);
    assert_eq!(problems, vec!["K4 has no case listing it in `behaviours`"]);
}

/// 29.9.26n AC6: the real spec, view and cases pass.
#[test]
fn the_vendored_spec_is_covered() {
    let (cases, errors) = case::load_dir(&repo().join("conformance/cases"));
    assert!(errors.is_empty(), "{errors:#?}");
    let problems = check(&read("spec/generator/openapi.3.1.json"), &cases);
    assert!(problems.is_empty(), "{problems:#?}");
}

/// 29.9.26n AC15: a case file with an unknown key is refused.
#[test]
fn an_unknown_case_field_is_refused() {
    let top = "id: k1.x\ntitle: t\nbehaviours: [K1]\nstpes: []\nsteps:\n  - advance_clock_s: 1\n";
    assert!(case::parse(top, Path::new("k1/x.yaml")).unwrap_err().contains("stpes"));
    let nested = "id: k1.x\ntitle: t\nbehaviours: [K1]\nsteps:\n  - call: { operation: getUsage, retry: 1 }\n    expect: { outcome: completed }\n";
    assert!(case::parse(nested, Path::new("k1/x.yaml")).unwrap_err().contains("retry"));
    let behaviour = "id: k1.x\ntitle: t\nbehaviours: [K7]\nsteps:\n  - advance_clock_s: 1\n";
    assert!(case::parse(behaviour, Path::new("k1/x.yaml")).is_err());
}

/// 29.9.26n AC16, 29.9.26ai AC5: an `endsOn` event missing from its entry's
/// `events` fails the guard; the guard itself names no terminal event.
#[test]
fn a_missing_terminal_event_fails() {
    assert!(terminal_problems(&view()).is_empty());
    let mut renamed = view();
    renamed["x-lingara-streams"][2]["events"] = json!(["started", "phase", "result", "waiting", "error"]);
    assert_eq!(
        terminal_problems(&renamed),
        vec!["`streamLessonPlan`: terminal event `pending` is not in the view's events"]
    );
    let mut invented = view();
    invented["x-lingara-streams"][0]["endsOn"] = json!(["finished", "error"]);
    assert_eq!(
        terminal_problems(&invented),
        vec!["`generateVocabulary`: terminal event `finished` is not in the view's events"]
    );
    let source = std::fs::read_to_string(repo().join("conformance/server/src/coverage.rs")).unwrap();
    for name in ["\"done\"", "\"result\"", "\"pending\""] {
        assert!(!source.contains(name), "coverage.rs names {name} itself");
    }
}

/// 29.9.26ai AC6: a keepalive longer than 15 s fails; none (`null`) passes.
#[test]
fn a_keepalive_longer_than_fifteen_seconds_fails() {
    let mut slow = view();
    slow["x-lingara-streams"][1]["keepaliveSeconds"] = json!(16);
    let problems = terminal_problems(&slow);
    assert_eq!(problems.len(), 1, "{problems:#?}");
    assert!(problems[0].starts_with("`createLessonPlan`: keepalive every 16 s exceeds 15 s"), "{}", problems[0]);
    assert!(view()["x-lingara-streams"][3]["keepaliveSeconds"].is_null());
    let mut exact = view();
    exact["x-lingara-streams"][1]["keepaliveSeconds"] = json!(15);
    assert!(terminal_problems(&exact).is_empty());
}

/// 29.9.26ai AC7, amended by 30.9.26aa AC13: a resumable entry passes only
/// as a K5a tail, whose `endsOn` holds its `error` (E3's `[done, error]`);
/// one whose `endsOn` does not fails, naming C2's resume decision.
#[test]
fn a_resumable_stream_passes_only_as_a_tail() {
    let mut tail = view();
    tail["x-lingara-streams"].as_array_mut().unwrap().push(json!({
        "operationId": "streamEvents", "events": ["event", "done", "error"], "endsOn": ["done", "error"],
        "error": "error", "keepaliveSeconds": 15, "resumable": true,
    }));
    assert!(terminal_problems(&tail).is_empty(), "{:#?}", terminal_problems(&tail));

    let mut no_error = tail.clone();
    no_error["x-lingara-streams"][4]["endsOn"] = json!(["done"]);
    let problems = terminal_problems(&no_error);
    assert_eq!(problems.len(), 1, "{problems:#?}");
    assert!(problems[0].starts_with("`streamEvents`: the stream is resumable but does not end on its error event"), "{}", problems[0]);
    assert!(problems[0].contains("Last-Event-ID"));

    let mut unnamed = tail;
    unnamed["x-lingara-streams"][4]["error"] = Value::Null;
    assert_eq!(terminal_problems(&unnamed).len(), 1, "a resumable entry with no error event fails");
}

/// 30.9.26aa AC14: a case set in which no case lists `K5a` fails the guard.
#[test]
fn a_k5a_behaviour_without_a_case_fails() {
    let problems = check(&view_with(&["getUsage"]), &[calling("getUsage", "[K1, K2, K3, K4, K5, K6]")]);
    assert_eq!(problems, vec!["K5a has no case listing it in `behaviours`"]);
    let k5a = "id: k5a.x\ntitle: t\nbehaviours: [K5a]\nsteps:\n  - tail: { take: 1 }\n    expect: { outcome: completed }\n";
    let parsed = case::parse(k5a, Path::new("k5a/x.yaml")).unwrap();
    assert_eq!(parsed.case.behaviours, vec![case::Behaviour::K5a]);
}
