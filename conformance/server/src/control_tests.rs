use serde_json::json;

use crate::control;
use crate::test_support::{body_json, loaded, send, start};

const CASE: &str = "
id: k2.sample
title: A sample with every value shape
behaviours: [K2, K6]
client:
  credentials: { client_id: lgr_cid_conformance00000000000, client_secret: lgr_cs_x, auth: post }
  version: 2026-09-knowing-tenpounder
steps:
  - call: { operation: getUsage }
    expect: { outcome: completed, status: 200, body: { allowance: [] }, sleeps_s: [] }
  - advance_clock_s: 600
exchanges:
  items:
    - request: { method: GET, path: /v1/usage }
      response: { status: 200, headers: { retry-after: 2 }, json: { allowance: [] } }
";

/// 29.9.26n AC20: `GET /__conformance/cases/{id}` returns JSON equal to the
/// parsed YAML, so no harness needs a YAML parser.
#[tokio::test]
async fn case_served_as_json() {
    let (port, _) = start(vec![loaded("k2/sample.yaml", CASE)]).await;
    let answer = send(port, "GET", "/__conformance/cases/k2.sample", &[]).await;
    assert!(answer.starts_with("HTTP/1.1 200 "), "{answer}");
    let yaml: serde_json::Value = serde_yaml::from_str(CASE).unwrap();
    assert_eq!(body_json(&answer), yaml);
    let list = send(port, "GET", "/__conformance/cases", &[]).await;
    assert_eq!(body_json(&list), json!(["k2.sample"]));
    let missing = send(port, "GET", "/__conformance/cases/k2.nope", &[]).await;
    assert!(missing.starts_with("HTTP/1.1 404 "), "{missing}");
}

#[tokio::test]
async fn a_second_arm_is_409_and_finish_disarms() {
    let (port, shared) = start(vec![loaded("k2/sample.yaml", CASE)]).await;
    let armed = send(port, "POST", "/__conformance/cases/k2.sample/arm", &[]).await;
    assert!(armed.starts_with("HTTP/1.1 200 "), "{armed}");
    let again = send(port, "POST", "/__conformance/cases/k2.sample/arm", &[]).await;
    assert!(again.starts_with("HTTP/1.1 409 "), "{again}");
    let finished = send(port, "POST", "/__conformance/cases/k2.sample/finish", &[]).await;
    let verdict = body_json(&finished);
    assert_eq!(verdict["case"], "k2.sample");
    assert_eq!(verdict["pass"], false, "the usage exchange was never consumed");
    assert!(shared.lock().armed.is_none());
    let log = shared.lock().log.clone();
    assert_eq!(log, vec![control::LogEntry::Arm("k2.sample".into()), control::LogEntry::Finish("k2.sample".into())]);
}
