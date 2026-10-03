use std::collections::HashMap;
use std::path::Path;

use reqwest::header::{HeaderMap, HeaderName, HeaderValue};
use serde_json::Value;

use super::{VerifyError, Webhook, WebhookHeaders};
use crate::BuildError;
use crate::events::Event;
use crate::fake_server::FakeClock;

/// The shared vectors, from the repository root. The packaged crate (the
/// MSRV job's build) carries no `conformance/`, so there the file is absent
/// and the test has nothing to read; in the workspace it must exist.
fn vectors() -> Option<Vec<Value>> {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("..");
    if !root.join("conformance").is_dir() {
        return None;
    }
    let path = root.join("conformance/vectors/webhook-signatures.json");
    let text = std::fs::read_to_string(&path).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    let file: Value = serde_json::from_str(&text).unwrap();
    Some(file["vectors"].as_array().cloned().unwrap())
}

fn header_map(headers: &HashMap<String, String>) -> HeaderMap {
    let mut map = HeaderMap::new();
    for (name, value) in headers {
        map.insert(HeaderName::try_from(name.as_str()).unwrap(), HeaderValue::from_str(value).unwrap());
    }
    map
}

/// `verify`'s and `verify_signature`'s results over one set of headers, as
/// the vectors spell them.
fn outcomes(webhook: &Webhook, body: &[u8], headers: &impl WebhookHeaders) -> (Result<Event, &'static str>, Result<(), &'static str>) {
    let verified = webhook.verify(body, headers).map_err(VerifyError::reason);
    let signed = webhook.verify_signature(body, headers).map_err(VerifyError::reason);
    (verified, signed)
}

/// 30.9.26aa AC24, and AC45's `verify_signature` half: every vector in
/// `conformance/vectors/webhook-signatures.json` gives its expected result
/// through `verify`, over both a `HashMap` and a `HeaderMap`; and
/// `verify_signature` passes every `ok` and `malformed_payload` vector and
/// raises every other vector's own reason. A `refused` vector's secrets are
/// refused at construction.
#[test]
fn every_shared_vector_verifies_as_expected() {
    let Some(vectors) = vectors() else { return };
    assert!(vectors.len() >= 28, "{} vectors", vectors.len());
    for v in &vectors {
        let name = v["name"].as_str().unwrap();
        let secrets: Vec<&str> = v["secrets"].as_array().unwrap().iter().map(|s| s.as_str().unwrap()).collect();
        let built = Webhook::with_secrets(&secrets);
        if v["expect"]["refused"] == true {
            assert!(matches!(built, Err(BuildError::InvalidWebhookSecret)), "{name}: {built:?}");
            continue;
        }
        let webhook = built.unwrap_or_else(|e| panic!("{name}: {e}")).clock(FakeClock::at(v["now"].as_u64().unwrap()));
        let headers: HashMap<String, String> = serde_json::from_value(v["headers"].clone()).unwrap();
        let body = v["body"].as_str().unwrap().as_bytes();
        let (verified, signed) = outcomes(&webhook, body, &headers);
        let (verified_map, signed_map) = outcomes(&webhook, body, &header_map(&headers));
        assert_eq!(verified.as_ref().map(Event::id).ok(), verified_map.as_ref().map(Event::id).ok(), "{name}: HeaderMap");
        assert_eq!(signed, signed_map, "{name}: HeaderMap");
        match v["expect"]["error"].as_str() {
            Some(reason) => {
                assert_eq!(verified.as_ref().err(), Some(&reason), "{name}");
                let want = if reason == "malformed_payload" { Ok(()) } else { Err(reason) };
                assert_eq!(signed, want, "{name}: verify_signature");
            }
            None => {
                let event = verified.unwrap_or_else(|r| panic!("{name}: {r}"));
                let ok = &v["expect"]["ok"];
                assert_eq!((event.id(), event.event_type()), (ok["id"].as_str().unwrap(), ok["type"].as_str().unwrap()), "{name}");
                assert_eq!(matches!(event, Event::Unknown(_)), ok["unknown"] == true, "{name}: unknown");
                assert_eq!(signed, Ok(()), "{name}: verify_signature");
            }
        }
    }
}

/// 30.9.26aa D4: no rendering of a verifier or of its refusal holds the
/// secret, and an empty list of secrets is refused like a bad one.
#[test]
fn secrets_are_redacted_and_an_empty_list_is_refused() {
    let secret = "lgr_whsec_Y29uZm9ybWFuY2Utd2ViaG9vay1zZWNyZXQtMDAwMSE=";
    let webhook = Webhook::new(secret).unwrap();
    for rendering in [format!("{webhook:?}"), format!("{webhook:#?}")] {
        assert!(!rendering.contains("Y29uZm9y"), "{rendering}");
    }
    let refused = Webhook::new("lgr_whsec_c2hvcnQ=").unwrap_err();
    assert!(!format!("{refused} {refused:?}").contains("c2hvcnQ"));
    assert!(matches!(Webhook::with_secrets(Vec::<String>::new()), Err(BuildError::InvalidWebhookSecret)));
}
