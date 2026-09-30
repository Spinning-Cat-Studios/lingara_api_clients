use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::header::{HeaderMap, HeaderValue};

use super::{DeprecationNotice, Reported, VersionObserver, deprecation_notice, parse_deprecation, parse_link, parse_sunset};
use crate::Client;
use crate::generated::spec_version::GENERATED_FOR_VERSION;
use crate::fake_server::{FakeServer, Reply};

const VERSIONS: &str = r#"{"current":"c","development":null,"versions":[]}"#;
const DETAIL: &str = r#"{"id":"2026-09-affable-cat","state":"deprecated","lts":false,"minted_at":"2026-09-01T00:00:00Z","summary":null,"sunset_at":null,"history":[],"spec":{"url":"/v1/openapi.json","sha256":null}}"#;
const LINK: &str = r#"</v1/versions/2026-09-affable-cat>; rel="deprecation"; type="application/json""#;

fn at(seconds: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(seconds)
}

fn headers(pairs: &[(&'static str, &'static str)]) -> HeaderMap {
    pairs.iter().map(|(n, v)| (n.parse().unwrap(), HeaderValue::from_static(v))).collect()
}

fn notice(version: Option<&str>) -> DeprecationNotice {
    let mut notice = deprecation_notice(&headers(&[("deprecation", "@1790812800")]), "http://x/v1/usage").unwrap();
    notice.version = version.map(str::to_owned);
    notice
}

/// 29.9.26p AC14: a `Deprecation` header calls the hook once with parsed
/// dates and a `Link` resolved against the request URL; a response without
/// one does not call it; an unparseable header leaves its field `None`; a
/// panicking hook does not fail the call; with no hook one warning is
/// logged per version id.
#[tokio::test]
async fn deprecation_hook_parsing_and_warn_once() {
    assert_eq!(parse_deprecation("@1790812800"), Some(at(1_790_812_800)));
    assert_eq!(parse_deprecation("last Tuesday"), None);
    assert_eq!(parse_sunset("Mon, 01 Mar 2027 00:00:00 GMT"), Some(at(1_803_859_200)));
    assert_eq!(parse_sunset("Monday, 01-Mar-27 00:00:00 GMT"), None, "RFC 850 is not an IMF-fixdate");
    assert_eq!(parse_sunset("soon"), None);
    let link = parse_link(LINK, "http://127.0.0.1:9/v1/usage");
    assert_eq!((link.raw.as_str(), link.target.unwrap().as_str()), (LINK, "http://127.0.0.1:9/v1/versions/2026-09-affable-cat"));
    assert!(deprecation_notice(&headers(&[("lingara-version", "v")]), "http://x/").is_none());

    let server = FakeServer::start(|req, _| match req.path.as_str() {
        "/v1/versions" => Reply::json(200, VERSIONS),
        "/v1/versions/unparseable" => Reply::with(200, &[("content-type", "application/json"), ("deprecation", "last Tuesday"), ("sunset", "soon")], DETAIL),
        _ => Reply::with(200, &[("content-type", "application/json"), ("lingara-version", "2026-09-affable-cat"), ("deprecation", "@1790812800"), ("sunset", "Mon, 01 Mar 2027 00:00:00 GMT"), ("link", LINK)], DETAIL),
    })
    .await;
    let calls: Arc<Mutex<Vec<DeprecationNotice>>> = Arc::default();
    let recorded = Arc::clone(&calls);
    let client = Client::builder().base_url(&server.url).on_deprecation(move |n| recorded.lock().unwrap().push(n.clone())).build().unwrap();

    client.list_api_versions().await.unwrap();
    assert!(calls.lock().unwrap().is_empty(), "no Deprecation, no call");

    client.get_api_version("deprecated").await.unwrap();
    let first = calls.lock().unwrap()[0].clone();
    assert_eq!(calls.lock().unwrap().len(), 1);
    assert_eq!((first.version.as_deref(), first.deprecated_at, first.sunset_at), (Some("2026-09-affable-cat"), Some(at(1_790_812_800)), Some(at(1_803_859_200))));
    let target = first.link.unwrap().target.unwrap();
    assert_eq!(target.as_str(), format!("{}/v1/versions/2026-09-affable-cat", server.url));

    client.get_api_version("unparseable").await.unwrap();
    let raw = calls.lock().unwrap()[1].clone();
    assert_eq!((raw.deprecated_at, raw.sunset_at, raw.deprecation.as_str(), raw.sunset.as_deref()), (None, None, "last Tuesday", Some("soon")));

    let panicking = Client::builder().base_url(&server.url).on_deprecation(|_| panic!("a hook that panics")).build().unwrap();
    assert!(panicking.get_api_version("deprecated").await.is_ok());

    let observer = VersionObserver::new(None);
    assert_eq!(observer.report(&notice(Some("2026-09-affable-cat"))), Reported::Warned);
    assert_eq!(observer.report(&notice(Some("2026-09-affable-cat"))), Reported::AlreadyWarned);
    assert_eq!(observer.report(&notice(Some("2026-09-other-id"))), Reported::Warned);
    assert_eq!(observer.report(&notice(None)), Reported::Warned);
    assert_eq!(observer.report(&notice(None)), Reported::AlreadyWarned);
}

/// 30.9.26a AC10: two responses echoing an id other than
/// `GENERATED_FOR_VERSION` log one mismatch warning; an echo of it, or no
/// echo, logs none; a deprecated and mismatched id logs both warnings (the
/// two dedup sets are separate); no `lingara-version` header is sent without
/// a configured version.
#[tokio::test]
async fn warns_once_when_served_another_version() {
    let observer = VersionObserver::new(None);
    let other = headers(&[("lingara-version", "2026-09-commending-possum")]);
    assert_eq!(observer.observe(&other, "http://x/v1/usage").as_deref(), Some("2026-09-commending-possum"));
    assert!(!observer.check_generated("2026-09-commending-possum"), "observe already warned for this id");
    observer.observe(&other, "http://x/v1/usage");
    assert!(observer.check_generated("2026-09-knowing-tenpounder"), "a second id warns once more");

    assert!(!observer.check_generated(GENERATED_FOR_VERSION), "the generated-for id never warns");
    assert_eq!(observer.observe(&HeaderMap::new(), "http://x/v1/usage"), None, "no echo, nothing to compare");

    let deprecated = notice(Some("2026-09-affable-cat"));
    assert_eq!(observer.report(&deprecated), Reported::Warned, "the deprecation warning");
    assert!(observer.check_generated("2026-09-affable-cat"), "and, separately, the mismatch warning");

    let server = FakeServer::start(|_, _| Reply::with(200, &[("content-type", "application/json"), ("lingara-version", "2026-09-commending-possum")], VERSIONS)).await;
    let client = Client::builder().base_url(&server.url).build().unwrap();
    client.list_api_versions().await.unwrap();
    assert_eq!(server.requests()[0].header("lingara-version"), None, "K2: no default pin");
}
