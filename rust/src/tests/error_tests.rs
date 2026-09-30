use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

use super::{Endpoint, Error, Refusal, TransportKind, from_refusal};
use crate::fake_server::{FakeServer, Reply};
use crate::{Client, TokenSource};

const SECRET: &str = "lgr_cs_redaction0000000000000000000000000000000";
const TOKEN: &str = "lgr_at_redaction";

fn refusal(endpoint: Endpoint, status: u16, json: bool) -> Refusal {
    Refusal { endpoint, status, json, retry_after: None, served_version: None }
}

#[test]
fn refusals_map_in_c2_d4_precedence() {
    let long = "维护".repeat(400);
    match from_refusal(refusal(Endpoint::Token, 503, false), &long) {
        Error::Maintenance(e) => assert!(e.body.len() <= 1024 && long.starts_with(&e.body)),
        other => panic!("{other:?}"),
    }
    let proxy = from_refusal(refusal(Endpoint::V1, 502, false), "<html>bad gateway</html>");
    assert!(matches!(proxy, Error::Api(e) if e.code == "http_502"));
    let envelope = from_refusal(refusal(Endpoint::V1, 403, true), r#"{"code":"insufficient_scope","error":"No."}"#);
    assert!(matches!(envelope, Error::Api(e) if e.code == "insufficient_scope" && e.message == "No."));
    let oauth = from_refusal(refusal(Endpoint::Token, 401, true), r#"{"error":"invalid_client","error_description":"Unknown client."}"#);
    assert!(matches!(oauth, Error::OAuth(e) if e.error == "invalid_client" && e.description.as_deref() == Some("Unknown client.")));
    let empty = from_refusal(refusal(Endpoint::Token, 500, false), "");
    assert!(matches!(empty, Error::OAuth(e) if e.error == "http_500" && e.description.is_none()));
}

fn renderings(value: &(impl std::fmt::Debug + ?Sized)) -> String {
    format!("{value:?}\n{value:#?}")
}

fn error_renderings(err: &Error) -> String {
    let mut out = format!("{err}\n{err:?}\n{err:#?}");
    let mut source = std::error::Error::source(err);
    while let Some(s) = source {
        out.push_str(&format!("\n{s}\n{s:?}"));
        source = s.source();
    }
    out
}

/// 29.9.26p AC8: neither the secret nor the token appears in the `{:?}` of
/// `Client`, `ClientCredentials`, `AccessToken` or any `Error`, nor in any
/// error's `{}`, and each rendering that holds one says `[REDACTED]`.
#[tokio::test]
async fn secrets_never_render() {
    let server = FakeServer::start(|req, _| match req.path.as_str() {
        "/oauth/token" => Reply::json(200, &format!(r#"{{"access_token":"{TOKEN}","token_type":"Bearer","expires_in":3600}}"#)),
        _ => Reply::json(403, r#"{"code":"insufficient_scope","error":"This call needs the usage:read scope."}"#),
    })
    .await;
    let client = Client::builder()
        .base_url(&server.url)
        .token_url(format!("{}/oauth/token", server.url))
        .client_credentials("lgr_cid_redaction000000000000", SECRET)
        .build()
        .unwrap();
    let err = client.get_usage().await.unwrap_err();
    let token = crate::AccessToken::new(TOKEN);

    let holders = [renderings(&client), renderings(&token)];
    for text in holders.iter().chain([&error_renderings(&err)]) {
        assert!(!text.contains(SECRET) && !text.contains(TOKEN), "leaked in: {text}");
    }
    for text in &holders {
        assert!(text.contains("[REDACTED]"), "no [REDACTED] in: {text}");
    }
    assert!(renderings(&client).contains("lgr_cid_redaction"), "the client id is not secret");

    // A token-endpoint refusal, under client_secret_post, renders nothing secret either.
    let tokens = crate::ClientCredentials::new("id".into(), SECRET.to_owned().into(), exchange_to(&server.url));
    let failure = tokens.token().await.unwrap_err();
    assert!(!error_renderings(&failure).contains(SECRET));
}

fn exchange_to(base: &str) -> crate::token::ExchangeConfig {
    use std::sync::Arc;
    crate::token::ExchangeConfig {
        http: reqwest::Client::new(),
        token_url: format!("{base}/refused"),
        user_agent: "test".into(),
        auth: crate::TokenAuth::Post,
        scopes: Vec::new(),
        policy: crate::retry::RetryPolicy {
            max_attempts: 1,
            retry_after_cap: Duration::from_secs(60),
            clock: Arc::new(crate::SystemClock),
            sleeper: Arc::new(crate::TokioSleeper),
        },
        request_timeout: Duration::from_secs(5),
    }
}

fn transport_kind(result: Result<impl std::fmt::Debug, Error>) -> TransportKind {
    match result {
        Err(Error::Transport(e)) => e.kind,
        other => panic!("expected a TransportError, got {other:?}"),
    }
}

/// 29.9.26p AC15: a listener that answers the ClientHello with garbage is
/// `Tls` under whichever TLS feature is built; a refused port, and a
/// listener that closes after reading the request, are both `Connect`.
#[tokio::test]
async fn transport_failures_map_to_their_kinds() {
    let garbage = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let https = format!("https://{}", garbage.local_addr().unwrap());
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = garbage.accept().await {
            let mut hello = [0u8; 512];
            let _ = socket.read(&mut hello).await;
            let _ = socket.write_all(b"HTTP/1.1 200 OK\r\n\r\nthis is not a TLS record\r\n").await;
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    });
    let tls = Client::builder().base_url(https).build().unwrap();
    assert_eq!(transport_kind(tls.list_api_versions().await), TransportKind::Tls);

    let closed = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let refused = format!("http://{}", closed.local_addr().unwrap());
    drop(closed);
    let client = Client::builder().base_url(refused).build().unwrap();
    assert_eq!(transport_kind(client.list_api_versions().await), TransportKind::Connect);

    let hangup = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", hangup.local_addr().unwrap());
    tokio::spawn(async move {
        while let Ok((mut socket, _)) = hangup.accept().await {
            let mut request = [0u8; 4096];
            let _ = socket.read(&mut request).await;
        }
    });
    let client = Client::builder().base_url(url).max_attempts(1).build().unwrap();
    assert_eq!(transport_kind(client.list_api_versions().await), TransportKind::Connect);
}
