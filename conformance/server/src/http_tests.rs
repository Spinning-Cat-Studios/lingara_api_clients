use crate::http::read_request;
use crate::test_support::{UA, body_json, send, start};

async fn read(raw: &[u8]) -> crate::http::HttpRequest {
    let mut reader = raw;
    read_request(&mut reader).await.unwrap().expect("a request")
}

#[tokio::test]
async fn a_content_length_body_is_read_and_the_query_split_off() {
    let raw = b"POST /oauth/token?x=1 HTTP/1.1\r\nHost: a\r\nContent-Length: 29\r\nUser-Agent: ua\r\n\r\ngrant_type=client_credentials";
    let request = read(raw).await;
    assert_eq!(request.method, "POST");
    assert_eq!(request.path, "/oauth/token");
    assert_eq!(request.query.as_deref(), Some("x=1"));
    assert_eq!(request.header("user-agent"), Some("ua"), "names are lowercased");
    assert_eq!(request.body, b"grant_type=client_credentials");
}

#[tokio::test]
async fn a_chunked_body_is_reassembled() {
    let raw = b"POST /v1/vocab/stream HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n6\r\n{\"leve\r\n6;ext=1\r\nl\": 2}\r\n0\r\n\r\n";
    assert_eq!(read(raw).await.body, b"{\"level\": 2}");
}

#[tokio::test]
async fn a_closed_connection_is_no_request_and_a_truncated_body_an_error() {
    let mut empty: &[u8] = b"";
    assert!(read_request(&mut empty).await.unwrap().is_none());
    let mut short: &[u8] = b"POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc";
    assert!(read_request(&mut short).await.is_err());
}

/// A replay-surface request with no case armed is refused 599 and kept as
/// a stray, which `run` reports.
#[tokio::test]
async fn a_request_with_nothing_armed_is_a_stray() {
    let (port, shared) = start(Vec::new()).await;
    let answer = send(port, "GET", "/v1/usage", &[("user-agent", UA)]).await;
    assert!(answer.starts_with("HTTP/1.1 599 "), "{answer}");
    assert!(body_json(&answer)["conformance_mismatch"].as_str().unwrap().contains("no case is armed"));
    assert_eq!(shared.lock().stray, vec!["GET /v1/usage: no case is armed".to_string()]);
}
