use base64::Engine as _;

use crate::control;
use crate::matcher::{Exchanges, basic_halves, check_user_agent};
use crate::test_support::{UA, body_json, loaded, request, send, start};

const GET_USAGE: &str = "
id: k1.usage
title: t
behaviours: [K1]
steps:
  - call: { operation: getUsage }
    expect: { outcome: completed }
exchanges:
  items:
    - request: { method: GET, path: /v1/usage }
      response: { status: 200, json: { allowance: [] } }
";

fn basic(id: &str, secret: &str) -> String {
    let token = base64::engine::general_purpose::STANDARD.encode(format!("{id}:{secret}"));
    format!("Basic {token}")
}

/// 29.9.26n AC7: an unmatched request is a mismatch and is answered 599
/// with a body naming the expected request.
#[tokio::test]
async fn unmatched_request_is_599_and_recorded() {
    let (port, shared) = start(vec![loaded("k1/usage.yaml", GET_USAGE)]).await;
    control::arm(&shared, "k1.usage");
    let answer = send(port, "GET", "/v1/versions", &[("user-agent", UA)]).await;
    assert!(answer.starts_with("HTTP/1.1 599 "), "{answer}");
    let body = body_json(&answer);
    assert_eq!(body["expected"], serde_json::json!(["GET /v1/usage"]));
    assert!(body["conformance_mismatch"].as_str().unwrap().contains("GET /v1/versions"));
    let verdict = control::finish(&shared, "k1.usage").await.1;
    assert_eq!(verdict["pass"], false);
    let mismatches = verdict["mismatches"].to_string();
    assert!(mismatches.contains("/v1/versions") && mismatches.contains("1 more expected"), "{mismatches}");
}

/// 29.9.26n AC8: each Basic half is form-decoded, so a secret with `+` and
/// `/` matches only when the library encoded it.
#[test]
fn basic_matcher_form_decodes_each_half() {
    let secret = "lgr_cs_conformance+secret/0";
    let encoded = basic("lgr_cid_x", "lgr_cs_conformance%2Bsecret%2F0");
    assert_eq!(basic_halves(&encoded), Some(("lgr_cid_x".into(), secret.into())));
    let raw = basic("lgr_cid_x", secret);
    assert_ne!(basic_halves(&raw), Some(("lgr_cid_x".into(), secret.into())));
    let case = loaded(
        "k1/basic.yaml",
        "
id: k1.basic
title: t
behaviours: [K1]
steps:
  - call: { operation: getUsage }
    expect: { outcome: completed }
exchanges:
  items:
    - request:
        method: POST
        path: /oauth/token
        headers: { authorization: { basic: [lgr_cid_x, 'lgr_cs_conformance+secret/0'] } }
      response: { status: 200 }
",
    );
    let mut exchanges = Exchanges::new(&case.case);
    let refused = request("POST", "/oauth/token", &[("authorization", &raw)], "");
    assert!(exchanges.take(&refused).is_err());
    let accepted = request("POST", "/oauth/token", &[("authorization", &encoded)], "");
    assert!(exchanges.take(&accepted).is_ok());
}

/// 29.9.26n AC11: `order: any` with `times: 8` takes eight requests and
/// refuses a ninth.
#[test]
fn any_order_counts_times() {
    let case = loaded(
        "k1/eight.yaml",
        "
id: k1.eight
title: t
behaviours: [K1]
steps:
  - call: { operation: getUsage, parallel: 8 }
    expect: { outcome: completed }
exchanges:
  order: any
  items:
    - times: 8
      request: { method: GET, path: /v1/usage }
      response: { status: 200, json: {} }
    - request: { method: POST, path: /oauth/token }
      response: { status: 200, json: {} }
",
    );
    let mut exchanges = Exchanges::new(&case.case);
    let usage = request("GET", "/v1/usage", &[], "");
    for _ in 0..4 {
        assert!(exchanges.take(&usage).is_ok());
    }
    assert!(exchanges.take(&request("POST", "/oauth/token", &[], "")).is_ok());
    for _ in 0..4 {
        assert!(exchanges.take(&usage).is_ok());
    }
    assert!(exchanges.unconsumed().is_empty());
    assert!(exchanges.take(&usage).is_err(), "a ninth is a mismatch");
}

/// 29.9.26n AC17: every replayed request's User-Agent is held to D8's
/// pattern, and a failure is a mismatch.
#[tokio::test]
async fn user_agent_checked_on_every_request() {
    for good in [UA, "lingara-php/1.0.0-rc.1 (php/8.3.1) kanji-quest/2.1", "lingara-go/0.3.0 (go1.22.1)"] {
        assert_eq!(check_user_agent(Some(good)), Ok(()), "{good}");
    }
    let long = format!("lingara-go/1.0.0-{} (go1.22.1)", "a".repeat(60));
    let bad = ["curl/8.4.0", "lingara-swift/0.1.0 (x)", "lingara-go/01.0.0 (go)", "lingara-go/0.1.0 (a)b)", &long];
    for bad in bad {
        assert!(check_user_agent(Some(bad)).is_err(), "{bad}");
    }
    assert!(check_user_agent(None).is_err());
    let (port, shared) = start(vec![loaded("k1/usage.yaml", GET_USAGE)]).await;
    control::arm(&shared, "k1.usage");
    let answer = send(port, "GET", "/v1/usage", &[("user-agent", "curl/8.4.0")]).await;
    assert!(answer.starts_with("HTTP/1.1 200 "), "the request still replays: {answer}");
    let verdict = control::finish(&shared, "k1.usage").await.1;
    assert_eq!(verdict["pass"], false);
    assert!(verdict["mismatches"].to_string().contains("User-Agent"));
}

/// 29.9.26n AC18: items in one group match in any order; a later group's
/// request before the earlier group is consumed is a mismatch.
#[test]
fn groups_order_between_and_not_within() {
    let case = loaded(
        "k1/groups.yaml",
        "
id: k1.groups
title: t
behaviours: [K1]
steps:
  - call: { operation: getUsage }
    expect: { outcome: completed }
exchanges:
  items:
    - { group: 1, request: { method: POST, path: /oauth/token }, response: { status: 200 } }
    - { group: 1, request: { method: GET, path: /v1/versions }, response: { status: 200 } }
    - { group: 2, request: { method: GET, path: /v1/usage }, response: { status: 200 } }
",
    );
    let mut exchanges = Exchanges::new(&case.case);
    assert!(exchanges.take(&request("GET", "/v1/versions", &[], "")).is_ok(), "any order within");
    let early = exchanges.take(&request("GET", "/v1/usage", &[], ""));
    assert!(early.is_err(), "group 2 before group 1 is consumed");
    assert_eq!(early.unwrap_err().expected, vec!["POST /oauth/token".to_string()]);
    assert!(exchanges.take(&request("POST", "/oauth/token", &[], "")).is_ok());
    assert!(exchanges.take(&request("GET", "/v1/usage", &[], "")).is_ok());
}

#[test]
fn header_json_form_and_query_matchers() {
    let case = loaded(
        "k1/shapes.yaml",
        "
id: k1.shapes
title: t
behaviours: [K1]
steps:
  - call: { operation: getUsage }
    expect: { outcome: completed }
exchanges:
  items:
    - request:
        method: POST
        path: /oauth/token
        headers:
          content-type: { prefix: application/x-www-form-urlencoded }
          lingara-version: { absent: true }
        form: { grant_type: client_credentials, scope: 'vocab:generate usage:read' }
      response: { status: 200 }
    - request: { method: POST, path: /v1/vocab/stream, json: { level: 2 } }
      response: { status: 200 }
",
    );
    let mut exchanges = Exchanges::new(&case.case);
    let form = "grant_type=client_credentials&scope=vocab%3Agenerate+usage%3Aread";
    let pinned = [("content-type", "application/x-www-form-urlencoded"), ("lingara-version", "x")];
    assert!(exchanges.take(&request("POST", "/oauth/token", &pinned, form)).is_err());
    let extra = format!("{form}&client_id=x");
    let ct = [("content-type", "application/x-www-form-urlencoded")];
    assert!(exchanges.take(&request("POST", "/oauth/token", &ct, &extra)).is_err(), "form is an exact set");
    assert!(exchanges.take(&request("POST", "/oauth/token", &ct, form)).is_ok());
    let mut with_query = request("POST", "/v1/vocab/stream", &[], "{\"level\": 2}");
    with_query.query = Some("x=1".into());
    assert!(exchanges.take(&with_query).is_err(), "no query expected");
    assert!(exchanges.take(&request("POST", "/v1/vocab/stream", &[], "{\"level\":3}")).is_err());
    assert!(exchanges.take(&request("POST", "/v1/vocab/stream", &[], "{\"level\": 2}")).is_ok());
}
