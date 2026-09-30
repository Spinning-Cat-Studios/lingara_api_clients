use crate::Client;
use crate::pipeline::{encode_segment, user_agent};
use crate::fake_server::{FakeServer, Reply};

const GRANT: &str = r#"{"access_token":"lgr_at_headers","token_type":"Bearer","expires_in":3600}"#;

/// C2 D8's pattern, checked by hand:
/// `lingara-rust/<semver> (<visible ASCII but ')'>)( <suffix>)?`.
fn matches_k6(value: &str) -> bool {
    let Some(rest) = value.strip_prefix("lingara-rust/") else { return false };
    let Some((version, rest)) = rest.split_once(" (") else { return false };
    let Some((runtime, tail)) = rest.split_once(')') else { return false };
    let semver = version.split('-').next().unwrap_or_default().split('.').filter(|n| n.parse::<u64>().is_ok()).count() == 3;
    let runtime_ok = !runtime.is_empty() && runtime.bytes().all(|b| (0x20..=0x7e).contains(&b));
    semver && runtime_ok && (tail.is_empty() || tail.starts_with(' '))
}

/// 29.9.26p AC12: the `User-Agent` matches C2 D8's pattern, its runtime is
/// `rust/<version>` with no `)`, and a suffix is appended after it.
#[test]
fn user_agent_leads_with_the_library_token() {
    let own = user_agent(None);
    assert!(matches_k6(&own), "{own}");
    assert!(own.starts_with(&format!("lingara-rust/{} (rust/", env!("CARGO_PKG_VERSION"))), "{own}");
    let runtime = own.split_once(" (").unwrap().1.strip_suffix(')').unwrap();
    assert!(!runtime.contains(')') && !runtime.contains('('), "{runtime}");
    let rustc = runtime.strip_prefix("rust/").unwrap().split(';').next().unwrap();
    assert!(rustc.starts_with(|c: char| c.is_ascii_digit()) || rustc == "unknown", "{rustc}");

    let suffixed = user_agent(Some("kanji-quest/2.1"));
    assert!(matches_k6(&suffixed), "{suffixed}");
    assert_eq!(suffixed, format!("{own} kanji-quest/2.1"));
}

/// 29.9.26p AC13: a JSON method's `ApiResponse` exposes `served_version`
/// from the echo, and a credential-free client calls `list_api_versions`
/// with no `Authorization` header.
#[tokio::test]
async fn served_version_and_the_credential_free_client() {
    let body = r#"{"current":"2026-09-knowing-tenpounder","development":"2026-09-glowing-hoatzin","versions":[]}"#;
    let server = FakeServer::start(move |_, _| Reply::with(200, &[("content-type", "application/json"), ("lingara-version", "2026-09-knowing-tenpounder")], body)).await;
    let client = Client::builder().base_url(format!("{}/", server.url)).build().unwrap();
    let versions = client.list_api_versions().await.unwrap();
    assert_eq!(versions.served_version(), Some("2026-09-knowing-tenpounder"));
    assert_eq!(versions.current.as_deref(), Some("2026-09-knowing-tenpounder"));
    let request = &server.requests()[0];
    assert_eq!(request.path, "/v1/versions");
    assert_eq!(request.header("authorization"), None);
}

/// 29.9.26p AC18: a pinned client sends `Lingara-Version` on every `/v1`
/// request and never to the token endpoint, and `User-Agent` goes to both.
#[tokio::test]
async fn headers_reach_the_right_endpoints() {
    let server = FakeServer::start(|req, _| match req.path.as_str() {
        "/oauth/token" => Reply::json(200, GRANT),
        _ => Reply::json(200, r#"{"allowance":[]}"#),
    })
    .await;
    let client = Client::builder()
        .base_url(&server.url)
        .token_url(format!("{}/oauth/token", server.url))
        .client_credentials("lgr_cid_headers", "lgr_cs_headers")
        .version("2026-09-affable-cat")
        .build()
        .unwrap();
    client.get_usage().await.unwrap();
    let requests = server.requests();
    let (token, usage) = (&requests[0], &requests[1]);
    assert_eq!((token.path.as_str(), usage.path.as_str()), ("/oauth/token", "/v1/usage"));
    assert_eq!((token.method.as_str(), usage.method.as_str()), ("POST", "GET"));
    assert_eq!(token.body, "grant_type=client_credentials");
    assert_eq!(token.header("lingara-version"), None);
    assert_eq!(usage.header("lingara-version"), Some("2026-09-affable-cat"));
    assert_eq!(usage.header("authorization"), Some("Bearer lgr_at_headers"));
    for request in &requests {
        assert!(request.header("user-agent").is_some_and(matches_k6), "{request:?}");
    }
}

#[test]
fn a_path_segment_is_percent_encoded() {
    assert_eq!(encode_segment("2026-09-knowing-tenpounder"), "2026-09-knowing-tenpounder");
    assert_eq!(encode_segment("a b/c?d"), "a%20b%2Fc%3Fd");
    assert_eq!(encode_segment("你"), "%E4%BD%A0");
}

#[test]
fn build_refuses_an_empty_version_and_a_bad_url() {
    assert!(matches!(Client::builder().version("").build(), Err(crate::BuildError::EmptyVersion)));
    assert!(matches!(Client::builder().base_url("not a url").build(), Err(crate::BuildError::InvalidUrl { option: "base_url", .. })));
}
