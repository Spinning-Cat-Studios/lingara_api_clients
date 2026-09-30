//! Shared fixtures for the server's unit tests.

use std::path::Path;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::case::{self, Loaded};
use crate::control::Shared;
use crate::http::HttpRequest;
use crate::serve;

/// A User-Agent D8's pattern admits.
pub const UA: &str = "lingara-rust/0.1.0 (rustc/1.90.0)";

/// Parses `yaml` as the case file at `rel` (`k1/x.yaml`), or panics.
pub fn loaded(rel: &str, yaml: &str) -> Loaded {
    case::parse(yaml, Path::new(rel)).unwrap_or_else(|e| panic!("{e}"))
}

/// A request as the matcher sees it.
pub fn request(method: &str, path: &str, headers: &[(&str, &str)], body: &str) -> HttpRequest {
    HttpRequest {
        method: method.into(),
        path: path.into(),
        query: None,
        headers: headers.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
        body: body.as_bytes().to_vec(),
    }
}

/// Starts a server over `cases` on a free port.
pub async fn start(cases: Vec<Loaded>) -> (u16, Shared) {
    let shared = Shared::new(cases);
    let (port, _handle) = serve::start(shared.clone(), 0).await.expect("the server binds");
    (port, shared)
}

/// Sends one raw request and reads the whole answer (the server closes).
pub async fn send(port: u16, method: &str, path: &str, headers: &[(&str, &str)]) -> String {
    let mut stream = TcpStream::connect(("127.0.0.1", port)).await.expect("connects");
    stream.write_all(raw(method, path, headers).as_bytes()).await.expect("writes");
    let mut out = Vec::new();
    let _ = stream.read_to_end(&mut out).await;
    String::from_utf8_lossy(&out).into_owned()
}

/// A request with no body, `connection: close`.
pub fn raw(method: &str, path: &str, headers: &[(&str, &str)]) -> String {
    let mut out = format!("{method} {path} HTTP/1.1\r\nhost: 127.0.0.1\r\ncontent-length: 0\r\n");
    for (k, v) in headers {
        out.push_str(&format!("{k}: {v}\r\n"));
    }
    out.push_str("\r\n");
    out
}

/// The JSON body of a whole response.
pub fn body_json(response: &str) -> serde_json::Value {
    let body = response.split_once("\r\n\r\n").map_or("", |(_, b)| b);
    serde_json::from_str(body).unwrap_or_else(|e| panic!("{e}: {response}"))
}
